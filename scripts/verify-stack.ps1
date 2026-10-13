# End-to-end verification of the transfer saga against the running compose stack.
#
#   docker compose up -d --build
#   ./scripts/verify-stack.ps1
#
# This is not a unit test and does not replace one. It drives the four real services over HTTP and
# then asks the ledger's own database what happened, which is the only way to check the claims no
# in-process test can support:
#
#   - that payments posts to ledger accounts that actually exist
#   - that the services can reach each other as configured
#   - that the real trial balance is zero after money has moved, and after it has moved back
#
# Both of those first two were real bugs, found here and not by 670 passing tests. See ADR-0017.
#
# Exits non-zero on the first failed assertion.

param(
    [string]$LedgerUrl   = "http://localhost:$($env:LEDGER_PORT   ?? '8080')",
    [string]$AccountsUrl = "http://localhost:$($env:ACCOUNTS_PORT ?? '8090')",
    [string]$PaymentsUrl = "http://localhost:$($env:PAYMENTS_PORT ?? '8100')"
)

$ErrorActionPreference = "Stop"
$script:failures = @()

function Invoke-Json($method, $url, $body, $headers) {
    $json = $body | ConvertTo-Json -Depth 6 -Compress
    return Invoke-RestMethod -Method $method -Uri $url -Body $json `
        -ContentType 'application/json' -Headers $headers
}

function Sql($query) {
    $raw = docker compose exec -T postgres psql -U ledger -d ledger -t -A -c $query
    return ($raw -join '').Trim()
}

# Every posting leg in the database, signed. Must be exactly zero at all times.
function TrialBalance {
    return [long](Sql "select coalesce(sum(case when side = 'DEBIT' then amount_minor else -amount_minor end), 0) from posting_leg")
}

function BalanceOf($account) {
    return [long](Sql "select coalesce(sum(case when side = 'DEBIT' then amount_minor else -amount_minor end), 0) from posting_leg where account_id = '$account'")
}

function Section($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }

function Check($description, $condition, $detail) {
    if ($condition) {
        Write-Host "  OK   $description" -ForegroundColor Green
    } else {
        Write-Host "  FAIL $description -- $detail" -ForegroundColor Red
        $script:failures += "$description ($detail)"
    }
}

# Opens a funded account and returns its IBAN plus the ledger account behind it.
function New-FundedAccount($name, $minorUnits) {
    # A fresh ledger account per run. The accounts service enforces one account per ledger
    # account — two pointing at the same one would both claim the same money — so reusing a
    # seeded id would make this script runnable exactly once.
    $ledgerAccount = "CUSTOMER" + ([guid]::NewGuid().ToString("N").Substring(0, 6).ToUpper())
    Sql "insert into ledger_account (id, account_class, currency) values ('$ledgerAccount', 'LIABILITY', 'EUR')" | Out-Null

    $customer = Invoke-Json POST "$AccountsUrl/accounts-api/customers" @{ name = $name } @{}
    Invoke-Json POST "$AccountsUrl/accounts-api/customers/$($customer.id)/verification" @{} @{} | Out-Null

    $account = Invoke-Json POST "$AccountsUrl/accounts-api/accounts" @{
        customerId = $customer.id; currency = "EUR"; ledgerAccountId = $ledgerAccount
    } @{}
    Invoke-Json POST "$AccountsUrl/accounts-api/accounts/$($account.iban)/activation" @{} @{} | Out-Null

    # A customer deposit is a liability (ADR-0005): the bank's cash rises and it owes the customer.
    Invoke-Json POST "$LedgerUrl/ledger/transactions" @{
        reference = "VERIFY-FUND-$([guid]::NewGuid())"
        legs = @(
            @{ account = "BANK.CASH.EUR"; side = "DEBIT";  amountMinorUnits = $minorUnits; currency = "EUR" },
            @{ account = $ledgerAccount;  side = "CREDIT"; amountMinorUnits = $minorUnits; currency = "EUR" }
        )
    } @{} | Out-Null

    return @{ Iban = $account.iban; LedgerAccount = $ledgerAccount }
}

function Send-Transfer($fromIban, $toIban, $minorUnits) {
    return Invoke-Json POST "$PaymentsUrl/payments/transfers" @{
        debtorIban       = $fromIban
        creditorIban     = $toIban
        amountMinorUnits = $minorUnits
        currency         = "EUR"
        requestedBy      = "verify-stack"
    } @{ "Idempotency-Key" = "verify-$([guid]::NewGuid())" }
}

# Beneficiaries. The happy path must never use an IBAN the scheme is configured to refuse, or it
# compensates and the section asserts the wrong thing — which is precisely what happened the first
# time this script was run.
$rejectedIbans = @()
if (-not [string]::IsNullOrWhiteSpace($env:PAYMENTS_REJECTED_IBANS)) {
    $rejectedIbans = ($env:PAYMENTS_REJECTED_IBANS -split ',') | ForEach-Object { $_.Trim().ToUpperInvariant() }
}

$candidates = @("NL91ABNA0417164300", "FR1420041010050500013M02606", "GB82WEST12345698765432", "BE68539007547034")
$happyPathBeneficiary = $candidates | Where-Object { $rejectedIbans -notcontains $_ } | Select-Object -First 1
if (-not $happyPathBeneficiary) {
    throw "every candidate beneficiary is in PAYMENTS_REJECTED_IBANS; the happy path cannot be tested"
}

# ---------------------------------------------------------------- 1. the happy path
Section "a SEPA transfer, end to end"
Check "the ledger starts balanced" ((TrialBalance) -eq 0) "trial balance was $(TrialBalance)"

$payer = New-FundedAccount "Verification Payer" 500000
$opening = BalanceOf $payer.LedgerAccount

$feeIncomeBefore = BalanceOf 'BANK.FEE.INCOME.EUR'
$transfer = Send-Transfer $payer.Iban $happyPathBeneficiary 10000
Write-Host "  $($payer.LedgerAccount) -> $happyPathBeneficiary"
Write-Host "  status:  $($transfer.status)"
Write-Host "  history: $(($transfer.history | ForEach-Object { $_.to }) -join ' -> ')"

Check "the transfer completed" ($transfer.status -eq "COMPLETED") "status was $($transfer.status)"
Check "it was routed as SEPA" ($transfer.scheme -eq "SEPA_CREDIT_TRANSFER") "scheme was $($transfer.scheme)"
Check "it names the ledger transaction that moved the money" `
    ([string]::IsNullOrEmpty($transfer.ledgerTransactionId) -eq $false) "no transaction id"
Check "the debtor paid the amount plus the fee" `
    (($opening - (BalanceOf $payer.LedgerAccount)) -eq -$transfer.totalDebitMinorUnits) `
    "moved $($opening - (BalanceOf $payer.LedgerAccount)), expected $(-$transfer.totalDebitMinorUnits)"
Check "the fee reached the bank's income account" `
    (($feeIncomeBefore - (BalanceOf 'BANK.FEE.INCOME.EUR')) -eq $transfer.feeMinorUnits) `
    "fee income moved by $($feeIncomeBefore - (BalanceOf 'BANK.FEE.INCOME.EUR')), expected $($transfer.feeMinorUnits)"
Check "the trial balance is still zero" ((TrialBalance) -eq 0) "trial balance was $(TrialBalance)"

# ---------------------------------------------------------------- 2. idempotency
Section "replaying an idempotency key"
$key = "verify-replay-$([guid]::NewGuid())"
$body = @{
    debtorIban = $payer.Iban; creditorIban = $happyPathBeneficiary
    amountMinorUnits = 10000; currency = "EUR"; requestedBy = "verify-stack"
}
$first  = Invoke-Json POST "$PaymentsUrl/payments/transfers" $body @{ "Idempotency-Key" = $key }
$replay = Invoke-Json POST "$PaymentsUrl/payments/transfers" $body @{ "Idempotency-Key" = $key }

Check "a replay returns the original transfer, not a new one" `
    ($first.id -eq $replay.id) "$($first.id) vs $($replay.id)"
Check "the trial balance is still zero" ((TrialBalance) -eq 0) "trial balance was $(TrialBalance)"

# ---------------------------------------------------------------- 3. compensation
Section "compensation"
$rejected = $env:PAYMENTS_REJECTED_IBANS
if ([string]::IsNullOrWhiteSpace($rejected)) {
    Write-Host "  SKIPPED -- the simulated scheme refuses nothing." -ForegroundColor Yellow
    Write-Host "  To exercise it:" -ForegroundColor Yellow
    Write-Host "    `$env:PAYMENTS_REJECTED_IBANS='DE89370400440532013000'" -ForegroundColor Yellow
    Write-Host "    docker compose up -d payments; ./scripts/verify-stack.ps1" -ForegroundColor Yellow
} else {
    $target = ($rejected -split ',')[0].Trim()
    $compPayer = New-FundedAccount "Compensation Payer" 500000
    $before = BalanceOf $compPayer.LedgerAccount
    $txBefore = [long](Sql "select count(*) from ledger_transaction")

    $comp = Send-Transfer $compPayer.Iban $target 10000
    Write-Host "  status:   $($comp.status)"
    Write-Host "  history:  $(($comp.history | ForEach-Object { $_.to }) -join ' -> ')"
    Write-Host "  posting:  $($comp.ledgerTransactionId)"
    Write-Host "  reversal: $($comp.reversalTransactionId)"

    Check "the saga ended COMPENSATED" ($comp.status -eq "COMPENSATED") "status was $($comp.status)"
    Check "the reversal is a separate transaction, not an edit (ADR-0011)" `
        ($comp.ledgerTransactionId -and $comp.reversalTransactionId -and
         $comp.ledgerTransactionId -ne $comp.reversalTransactionId) "ids missing or identical"
    Check "two transactions were recorded, nothing deleted" `
        (([long](Sql "select count(*) from ledger_transaction") - $txBefore) -eq 2) `
        "recorded $([long](Sql 'select count(*) from ledger_transaction') - $txBefore)"
    Check "the debtor is whole again, fee included" `
        ((BalanceOf $compPayer.LedgerAccount) -eq $before) `
        "$before -> $(BalanceOf $compPayer.LedgerAccount)"
    Check "the trial balance is still zero" ((TrialBalance) -eq 0) "trial balance was $(TrialBalance)"
}

# ---------------------------------------------------------------- verdict
Section "result"
if ($script:failures.Count -gt 0) {
    Write-Host "$($script:failures.Count) assertion(s) failed:" -ForegroundColor Red
    $script:failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "  All assertions passed against the live stack." -ForegroundColor Green
Write-Host "  Final trial balance: $(TrialBalance)" -ForegroundColor Green
