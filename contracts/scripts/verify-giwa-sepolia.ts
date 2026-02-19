import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import "dotenv/config";
import hre from "hardhat";

type DeployArtifact = {
  contracts: {
    KYCRegistry: string;
    MockUSD: string;
    PropertyRegistry: string;
    PropertyShare1155: string;
    PropertyTokenizer: string;
    FixedPriceMarketDvP: string;
  };
  shareBaseURI?: string;
};

async function verify(address: string, label: string, args: unknown[]) {
  await hre.run("verify:verify", {
    address,
    contract: `contracts/${label}.sol:${label}`,
    constructorArguments: args,
  });
  console.log(`✅ Verified ${label}`);
}

async function main() {
  const file = path.join(process.cwd(), "deployments", "giwa-sepolia.json");
  if (!existsSync(file)) {
    throw new Error(`Missing deployment artifact: ${file}`);
  }

  const raw = readFileSync(file, "utf-8");
  const deployment = JSON.parse(raw) as DeployArtifact;
  if (!deployment.contracts) {
    throw new Error("Invalid deployment artifact");
  }

  await verify(deployment.contracts.KYCRegistry, "KYCRegistry", []);
  await verify(deployment.contracts.MockUSD, "MockUSD", []);
  await verify(deployment.contracts.PropertyRegistry, "PropertyRegistry", []);
  await verify(deployment.contracts.PropertyShare1155, "PropertyShare1155", [
    deployment.shareBaseURI || "",
    deployment.contracts.KYCRegistry,
  ]);
  await verify(deployment.contracts.PropertyTokenizer, "PropertyTokenizer", [
    deployment.contracts.PropertyRegistry,
    deployment.contracts.PropertyShare1155,
  ]);
  await verify(deployment.contracts.FixedPriceMarketDvP, "FixedPriceMarketDvP", []);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
