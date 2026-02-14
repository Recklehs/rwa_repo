import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { verifyContract } from "@nomicfoundation/hardhat-verify/verify";
import hre, { network } from "hardhat";

type DeploymentArtifact = {
  contracts: {
    KYCRegistry: string;
    MockUSD: string;
    PropertyRegistry: string;
    PropertyShare1155: string;
    PropertyTokenizer: string;
    FixedPriceMarketDvP: string;
  };
};

type VerifyTarget = {
  name: string;
  address: string;
  constructorArguments: unknown[];
};

function isAlreadyVerified(error: unknown): boolean {
  const message = String(error);
  return /already verified|already been verified|smart-contract already verified/i.test(message);
}

async function main(): Promise<void> {
  const connection = await network.connect();
  const networkName = connection.networkName;
  const __filename = fileURLToPath(import.meta.url);
  const __dirname = path.dirname(__filename);
  const outputFileName = networkName === "giwaSepolia" ? "giwa-sepolia.json" : `${networkName}.json`;
  const deploymentPath = path.resolve(__dirname, "..", "deployments", outputFileName);
  if (!fs.existsSync(deploymentPath)) {
    throw new Error(`Deployment artifact not found: ${deploymentPath}`);
  }

  const deployment = JSON.parse(fs.readFileSync(deploymentPath, "utf8")) as DeploymentArtifact;
  const baseURI = process.env.PROPERTY_SHARE_BASE_URI || "ipfs://rwa/property-share";

  const targets: VerifyTarget[] = [
    {
      name: "KYCRegistry",
      address: deployment.contracts.KYCRegistry,
      constructorArguments: []
    },
    {
      name: "MockUSD",
      address: deployment.contracts.MockUSD,
      constructorArguments: []
    },
    {
      name: "PropertyRegistry",
      address: deployment.contracts.PropertyRegistry,
      constructorArguments: []
    },
    {
      name: "PropertyShare1155",
      address: deployment.contracts.PropertyShare1155,
      constructorArguments: [deployment.contracts.KYCRegistry, baseURI]
    },
    {
      name: "PropertyTokenizer",
      address: deployment.contracts.PropertyTokenizer,
      constructorArguments: [deployment.contracts.PropertyRegistry, deployment.contracts.PropertyShare1155]
    },
    {
      name: "FixedPriceMarketDvP",
      address: deployment.contracts.FixedPriceMarketDvP,
      constructorArguments: []
    }
  ];

  for (const target of targets) {
    try {
      console.log(`Verifying ${target.name} at ${target.address}`);
      await verifyContract(
        {
          address: target.address,
          constructorArgs: target.constructorArguments,
          provider: "blockscout"
        },
        hre
      );
      console.log(`Verified ${target.name}`);
    } catch (error) {
      if (isAlreadyVerified(error)) {
        console.log(`${target.name} is already verified`);
        continue;
      }

      throw error;
    }
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
