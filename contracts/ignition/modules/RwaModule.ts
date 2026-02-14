import { buildModule } from "@nomicfoundation/hardhat-ignition/modules";

const RwaModule = buildModule("RwaModule", (m) => {
  const baseURI = m.getParameter("baseURI", "ipfs://rwa/property-share");

  const kyc = m.contract("KYCRegistry", [], { id: "KYCRegistry" });
  const mockUsd = m.contract("MockUSD", [], { id: "MockUSD", after: [kyc] });
  const registry = m.contract("PropertyRegistry", [], { id: "PropertyRegistry", after: [mockUsd] });
  const share = m.contract("PropertyShare1155", [kyc, baseURI], {
    id: "PropertyShare1155",
    after: [registry]
  });
  const tokenizer = m.contract("PropertyTokenizer", [registry, share], {
    id: "PropertyTokenizer",
    after: [share]
  });
  const market = m.contract("FixedPriceMarketDvP", [], {
    id: "FixedPriceMarketDvP",
    after: [tokenizer]
  });

  return {
    kyc,
    mockUsd,
    registry,
    share,
    tokenizer,
    market
  };
});

export default RwaModule;
