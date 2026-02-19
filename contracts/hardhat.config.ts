import "dotenv/config";
import { HardhatUserConfig } from "hardhat/config";

const PRIVATE_KEY = process.env.PRIVATE_KEY;
const accounts = PRIVATE_KEY ? [PRIVATE_KEY] : [];

const config: HardhatUserConfig = {
  solidity: "0.8.28",
  defaultNetwork: "hardhat",
  networks: {
    hardhat: {
      chainId: 31337,
    },
    giwaSepolia: {
      url: process.env.GIWA_SEPOLIA_RPC_URL || "https://sepolia-rpc.giwa.io",
      chainId: 91342,
      chainType: "op",
      accounts,
    },
  },
  etherscan: {
    apiKey: {
      giwaSepolia: process.env.BLOCKSCOUT_API_KEY || "NO_KEY",
    },
    customChains: [
      {
        network: "giwaSepolia",
        chainId: 91342,
        urls: {
          apiURL: "https://sepolia-explorer.giwa.io/api",
          browserURL: "https://sepolia-explorer.giwa.io",
        },
      },
    ],
  },
};

export default config;
