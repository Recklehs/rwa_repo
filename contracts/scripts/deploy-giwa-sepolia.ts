import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import "dotenv/config";
import hre from "hardhat";
import giwaSepoliaModule from "../ignition/modules/giwaSepolia";

const MODULE_NAME = "GIWASepoliaDeployment";

async function main() {
  const [deployer] = await hre.ethers.getSigners();
  const treasury =
    process.env.TREASURY_ADDRESS || (await deployer.getAddress());
  const issuer = process.env.ISSUER_ADDRESS || (await deployer.getAddress());
  const shareBaseURI = process.env.SHARE_BASE_URI || "";

  const deployment = await hre.ignition.deploy(giwaSepoliaModule, {
    parameters: {
      [MODULE_NAME]: {
        shareBaseURI,
        treasury,
        issuer,
      },
    },
  });

  const artifacts = {
    chainId: 91342,
    network: "giwaSepolia",
    explorer: "https://sepolia-explorer.giwa.io",
    deployedAt: new Date().toISOString(),
    treasury,
    issuer,
    shareBaseURI,
    contracts: {
      KYCRegistry: await deployment.kycRegistry.getAddress(),
      MockUSD: await deployment.mockUsd.getAddress(),
      PropertyRegistry: await deployment.propertyRegistry.getAddress(),
      PropertyShare1155: await deployment.propertyShare.getAddress(),
      PropertyTokenizer: await deployment.tokenizer.getAddress(),
      FixedPriceMarketDvP: await deployment.market.getAddress(),
    },
  };

  const outputPath = path.join(process.cwd(), "deployments", "giwa-sepolia.json");
  mkdirSync(path.dirname(outputPath), { recursive: true });
  writeFileSync(outputPath, `${JSON.stringify(artifacts, null, 2)}\n`);

  console.log(`Deployment artifact written to ${outputPath}`);
  console.log(`KYCRegistry: ${artifacts.contracts.KYCRegistry}`);
  console.log(`MockUSD: ${artifacts.contracts.MockUSD}`);
  console.log(`PropertyRegistry: ${artifacts.contracts.PropertyRegistry}`);
  console.log(`PropertyShare1155: ${artifacts.contracts.PropertyShare1155}`);
  console.log(`PropertyTokenizer: ${artifacts.contracts.PropertyTokenizer}`);
  console.log(`FixedPriceMarketDvP: ${artifacts.contracts.FixedPriceMarketDvP}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
