import { formatEther, getAddress, isAddress, JsonRpcProvider, parseEther, Wallet } from "ethers";

function requireEnv(key: string): string {
  const value = process.env[key]?.trim();
  if (!value) {
    throw new Error(`Set ${key} env var.`);
  }
  return value;
}

function readSenderPrivateKey(): string {
  const key = process.env.FROM_PRIVATE_KEY?.trim() || process.env.PRIVATE_KEY?.trim();
  if (!key) {
    throw new Error("Set FROM_PRIVATE_KEY (or PRIVATE_KEY) env var.");
  }
  if (!/^0x[a-fA-F0-9]{64}$/.test(key)) {
    throw new Error("Invalid private key format. Expected 0x-prefixed 64 hex chars.");
  }
  return key;
}

function readAmountWei(): bigint {
  const amountEth = requireEnv("AMOUNT_ETH");
  const amountWei = parseEther(amountEth);
  if (amountWei <= 0n) {
    throw new Error("AMOUNT_ETH must be greater than 0.");
  }
  return amountWei;
}

async function main() {
  const toRaw = requireEnv("TO_ADDRESS");
  if (!isAddress(toRaw)) {
    throw new Error(`Invalid TO_ADDRESS: ${toRaw}`);
  }
  const to = getAddress(toRaw);
  const amountWei = readAmountWei();
  const privateKey = readSenderPrivateKey();

  const rpcUrl = process.env.GIWA_SEPOLIA_RPC_URL || "https://sepolia-rpc.giwa.io";
  const chainIdRaw = process.env.GIWA_SEPOLIA_CHAIN_ID || "91342";
  const chainId = Number(chainIdRaw);
  if (!Number.isInteger(chainId) || chainId <= 0) {
    throw new Error(`Invalid GIWA_SEPOLIA_CHAIN_ID: ${chainIdRaw}`);
  }

  const provider = new JsonRpcProvider(rpcUrl, chainId);
  const sender = new Wallet(privateKey, provider);
  const from = await sender.getAddress();

  const fromBefore = await provider.getBalance(from);
  const toBefore = await provider.getBalance(to);

  console.log(`networkChainId: ${chainId}`);
  console.log(`from: ${from}`);
  console.log(`to: ${to}`);
  console.log(`amountWei: ${amountWei}`);
  console.log(`amountEth: ${formatEther(amountWei)}`);
  console.log(`fromBalanceBeforeEth: ${formatEther(fromBefore)}`);
  console.log(`toBalanceBeforeEth: ${formatEther(toBefore)}`);

  const tx = await sender.sendTransaction({
    to,
    value: amountWei
  });

  console.log(`txHash: ${tx.hash}`);
  const receipt = await tx.wait(1);
  if (!receipt || receipt.status !== 1) {
    throw new Error("Transfer tx failed or reverted.");
  }

  const fromAfter = await provider.getBalance(from);
  const toAfter = await provider.getBalance(to);
  console.log(`blockNumber: ${receipt.blockNumber}`);
  console.log(`fromBalanceAfterEth: ${formatEther(fromAfter)}`);
  console.log(`toBalanceAfterEth: ${formatEther(toAfter)}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

