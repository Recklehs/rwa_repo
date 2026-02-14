# Codex Spec Tracker

Last updated: 2026-02-14

## Original User Spec (Initial)

### Goal
- Implement 6 core contracts with OpenZeppelin:
1. KYCRegistry
2. MockUSD (ERC-20)
3. PropertyRegistry (Class registry)
4. PropertyShare1155 (ERC-1155 + allowlist transfer restriction)
5. PropertyTokenizer (range allocation + register class + mint batches)
6. FixedPriceMarketDvP (ERC1155 escrow + ERC20 payment, DvP atomic buy)

### Tech
- Hardhat project (TypeScript), Solidity 0.8.28
- OpenZeppelin Contracts
- Use Hardhat Ignition for deployment
- Provide verification scripts for Blockscout explorer

### Suggested repo layout (contracts module)
- contracts/contracts/*.sol
- contracts/test/*.ts
- contracts/ignition/modules/*.ts
- contracts/hardhat.config.ts
- contracts/.env.example

### Contract specs

#### 1) KYCRegistry.sol
- Admin-only mutation (Ownable or AccessControl)
- `mapping(address => bool) allowed`
- `setAllowed(address user, bool allowed)` onlyOwner
- `batchSetAllowed(address[] users, bool allowed)` onlyOwner
- `isAllowed(address user) view returns (bool)`
- Event: `AllowedSet(address indexed user, bool allowed)`

#### 2) MockUSD.sol
- ERC20 + Ownable
- `decimals = 18`
- `mint(address to, uint256 amount)` onlyOwner

#### 3) PropertyRegistry.sol
- onlyOwner class management
- `ClassInfo { docHash, unitCount, baseTokenId, status, issuedAt }`
- `registerClass(classId, docHash, unitCount, baseTokenId, issuedAt)` onlyOwner
- `setStatus(classId, status)` onlyOwner
- `updateDocHash(classId, newDocHash)` onlyOwner
- `getClass(classId) view returns (ClassInfo)`
- Events:
  - `ClassRegistered`
  - `DocHashUpdated`
  - `ClassStatusChanged`

#### 4) PropertyShare1155.sol
- ERC1155 + ERC1155Supply + Ownable
- immutable `KYCRegistry kyc`
- constant `SHARE_SCALE = 1e18`
- Transfer restriction for non-mint/non-burn:
  - require `kyc.isAllowed(operator)`
  - require `kyc.isAllowed(from)`
  - require `kyc.isAllowed(to)`
- `mintBatch(to, ids, amounts)` onlyOwner
- Optional burn support
- URI support: `${baseURI}/${id}.json`

#### 5) PropertyTokenizer.sol
- onlyOwner issuer flow
- refs: `PropertyRegistry registry`, `PropertyShare1155 share`
- state: `nextBaseTokenId` starts at 1
- `reserveAndRegisterClass(...)`:
  - require `unitCount > 0`
  - allocate `[baseTokenId, baseTokenId + unitCount - 1]`
  - register class
  - emit `ClassReserved`
- `mintUnits(classId, startOffset, count, treasury)`:
  - validate range within class unitCount
  - mint ids `baseTokenId + startOffset + i`
  - amount per unit is `SHARE_SCALE`
  - emit `UnitsMinted`

#### 6) FixedPriceMarketDvP.sol
- ReentrancyGuard + ERC1155Receiver
- SafeERC20 for payments
- Listing struct with seller/shareToken/tokenId/payToken/unitPrice/total/remaining/status
- `nextListingId` starts at 1
- `list(...)`:
  - validate amount and unitPrice
  - escrow ERC1155 into market
  - emit `Listed`
- `buy(listingId, amount)`:
  - require active and sufficient remaining
  - `cost = mulDiv(amount, unitPrice, SHARE_SCALE)`
  - effects first then interactions
  - transfer ERC20 buyer -> seller and ERC1155 market -> buyer
  - emit `Bought`
- `cancel(listingId)`:
  - only seller and active listing
  - return remaining escrow to seller
  - emit `Cancelled`
- Must implement valid ERC1155 receiver selectors

### Hardhat config and deployment
- Add `giwaSepolia` network:
  - RPC: `https://sepolia-rpc.giwa.io`
  - `chainType: "op"`
  - chain id descriptor `91342`
  - Blockscout verifier API URL
- Use `.env` with deployer `PRIVATE_KEY`
- Deployment order:
1. KYCRegistry
2. MockUSD
3. PropertyRegistry
4. PropertyShare1155(kyc)
5. PropertyTokenizer(registry, share)
6. FixedPriceMarketDvP
- Bootstrap:
  - `KYCRegistry.setAllowed(market, true)`
  - `KYCRegistry.setAllowed(treasury, true)`
  - `KYCRegistry.setAllowed(issuer, true)`
- Output deployment JSON artifact:
  - `contracts/deployments/giwa-sepolia.json`

### Required tests
- allowlist enforcement where non-allowlisted market causes transfer/list/buy revert
- DvP atomicity:
  - fail on insufficient ERC20 allowance with no partial transfers
  - fail on insufficient escrow with no ERC20 transfer persistence
- partial fills update `remainingAmount`
- cancel returns remaining shares
- tokenizer range allocation + mint IDs correctness

### Acceptance criteria
- all tests pass
- deploy to GIWA Sepolia succeeds
- contracts verified (optional but recommended)
- list/buy works on GIWA Sepolia and emits events

## Spec Updates During Collaboration

### 2026-02-14 update: ownership handoff enforcement
- Deployment/bootstrap flow must enforce final ownership:
  - `KYCRegistry.owner = issuerAddress`
  - `MockUSD.owner = issuerAddress`
  - `PropertyTokenizer.owner = issuerAddress`
  - `PropertyRegistry.owner = PropertyTokenizer address`
  - `PropertyShare1155.owner = PropertyTokenizer address`
  - If market is Ownable, market owner should be `issuerAddress`
- Bootstrap must execute ordered steps:
1. `setAllowed(market, treasury, issuer, true)`
2. `transferOwnership(PropertyRegistry -> Tokenizer)`
3. `transferOwnership(PropertyShare1155 -> Tokenizer)`
4. `transferOwnership(KYCRegistry -> issuer)`
5. `transferOwnership(MockUSD -> issuer)`
6. `transferOwnership(Tokenizer -> issuer)`
7. Assert owner values and fail if mismatch

## Progress Updates

### 2026-02-14
- Implemented all 6 contracts under `contracts/contracts/`.
- Added Hardhat tests under `contracts/test/rwa.spec.ts` covering all required scenarios.
- Added Ignition deployment module at `contracts/ignition/modules/RwaModule.ts`.
- Added scripts:
  - `contracts/scripts/bootstrap-giwa.ts`
  - `contracts/scripts/export-deployment.ts`
  - `contracts/scripts/verify-giwa.ts`
  - `contracts/scripts/export-abi.ts`
- Added env template and module docs:
  - `contracts/.env.example`
  - `contracts/README.md`
- Added deployment artifact placeholders:
  - `contracts/deployments/giwa-sepolia.json`
  - `shared/deployments/giwa-sepolia.json`

### 2026-02-14 (Hardhat 3 migration)
- Migrated project to Hardhat 3 stack:
  - `hardhat@3.x`
  - `@nomicfoundation/hardhat-ethers`
  - `@nomicfoundation/hardhat-ethers-chai-matchers`
  - `@nomicfoundation/hardhat-mocha`
  - `@nomicfoundation/hardhat-ignition`
  - `@nomicfoundation/hardhat-ignition-ethers`
  - `@nomicfoundation/hardhat-verify`
- Converted config to Hardhat 3 plugin model and network typing.
- Updated tests/scripts to Hardhat 3 APIs (`network.connect()` and `verifyContract`).
- Updated tests for Hardhat 3 matcher deprecations (`.revert(ethers)`).
- Validation status after migration:
  - `npm run test`: 7 passing
  - `npm run compile`: passing
- Remaining warning: local Node runtime is `23.x` (unsupported by Hardhat); use Node `22.10+` or `24.x` LTS.

### 2026-02-14 (Ownership handoff fix)
- Removed ownership transfer calls from Ignition module so bootstrap controls handoff order:
  - `contracts/ignition/modules/RwaModule.ts`
- Reworked `contracts/scripts/bootstrap-giwa.ts` to enforce the required sequence:
1. allowlist `market`, `treasury`, `issuer`
2. transfer `PropertyRegistry.owner -> PropertyTokenizer`
3. transfer `PropertyShare1155.owner -> PropertyTokenizer`
4. transfer `KYCRegistry.owner -> issuer`
5. transfer `MockUSD.owner -> issuer`
6. transfer `PropertyTokenizer.owner -> issuer`
7. assert all expected owners; throw on mismatch
- Added optional market owner assertion path:
  - if `owner()` exists on market contract, assert it equals issuer
- Changed bootstrap address handling to strict validation:
  - `ISSUER_ADDRESS` and `TREASURY_ADDRESS` must be valid non-zero addresses
- Updated docs/env examples to reflect strict bootstrap requirements.

### 2026-02-14 (Local network validation run)
- Added `localhost` network config for persistent local-chain dry runs.
- Fixed deployment/bootstrap/verify scripts to derive `networkName` from `network.connect()` so file output uses the actual network (e.g., `localhost.json`).
- Executed localhost flow against a running Hardhat node:
1. `npx hardhat ignition deploy ignition/modules/RwaModule.ts --network localhost`
2. `npx hardhat run scripts/export-deployment.ts --network localhost`
3. `ISSUER_ADDRESS=<acct1> TREASURY_ADDRESS=<acct2> npx hardhat run scripts/bootstrap-giwa.ts --network localhost`
- Result:
  - allowlist step succeeded for market/treasury/issuer
  - ownership transfer sequence executed in required order
  - final owner assertions passed

### 2026-02-14 (Gas estimation report on local Hardhat)
- Added dedicated gas-report test:
  - `contracts/test/gas-report.spec.ts`
- Test executes:
  - deployment of all 6 contracts
  - representative state-changing txs for each core contract function family
  - market flow (`list`, `buy`, `cancel`)
  - ownership handoff txs used in bootstrap path
- Test writes gas logs to:
  - `contracts/logs/gas/hardhat-gas-report.json`
  - `contracts/logs/gas/hardhat-gas-report.md`
- Local run result:
  - `npm run test -- test/gas-report.spec.ts`: 1 passing
  - measured tx count: 24
  - total gas used: `6,086,486`
  - sample total fee estimates:
    - at `0.1 gwei`: `0.00060864 ETH`
    - at `1 gwei`: `0.00608648 ETH`
    - at `5 gwei`: `0.03043243 ETH`
- Note:
  - this is a local Hardhat profile and does not include live GIWA Sepolia L2/L1 fee dynamics.

## Pending / Next Milestones
- Run live deploy to GIWA Sepolia.
- Run bootstrap allowlist txs on GIWA Sepolia.
- Verify all contracts on GIWA Blockscout.
- Validate on-chain list/buy event flow.

## Update Policy
- This file is the canonical tracker for this request.
- It will be updated as we make implementation, testing, deployment, and verification progress.
