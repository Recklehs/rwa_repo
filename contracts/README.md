# RWA Contracts (Hardhat)

Requirements:
- Hardhat 3.x
- Node.js 22.10+ (or newer even-major LTS, e.g. 24.x)

## Setup

```bash
cd /Users/kanghyoseung/Desktop/project/rwa/contracts
cp .env.example .env
npm install
```

## Compile & test

```bash
npm run compile
npm run test
```

## Deploy to GIWA Sepolia

```bash
npm run deploy:giwa
npm run export:deployment:giwa
npm run bootstrap:giwa
```

The bootstrap script allowlists:
- deployed `FixedPriceMarketDvP`
- `TREASURY_ADDRESS`
- `ISSUER_ADDRESS`

Bootstrap then enforces ownership handoff:
- `PropertyRegistry.owner -> PropertyTokenizer`
- `PropertyShare1155.owner -> PropertyTokenizer`
- `KYCRegistry.owner -> ISSUER_ADDRESS`
- `MockUSD.owner -> ISSUER_ADDRESS`
- `PropertyTokenizer.owner -> ISSUER_ADDRESS`

`ISSUER_ADDRESS` and `TREASURY_ADDRESS` must be set to non-zero addresses.

## Verify on Blockscout

```bash
npm run verify:giwa
```

## Export ABI for shared modules

```bash
npm run export:abi
```

ABIs are written into:
- `/Users/kanghyoseung/Desktop/project/rwa/shared/abi`

Deployment artifacts are written into:
- `/Users/kanghyoseung/Desktop/project/rwa/contracts/deployments/giwa-sepolia.json`
- `/Users/kanghyoseung/Desktop/project/rwa/shared/deployments/giwa-sepolia.json`
