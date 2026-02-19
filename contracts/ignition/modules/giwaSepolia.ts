import { buildModule } from "@nomicfoundation/hardhat-ignition/modules";

const MODULE_NAME = "GIWASepoliaDeployment";

const giwaSepoliaModule = buildModule(MODULE_NAME, (m) => {
  const kycRegistry = m.contract("KYCRegistry");
  const mockUsd = m.contract("MockUSD");
  const propertyRegistry = m.contract("PropertyRegistry");
  const propertyShare = m.contract("PropertyShare1155", [
    m.getParameter("shareBaseURI", ""),
    kycRegistry,
  ]);
  const tokenizer = m.contract("PropertyTokenizer", [propertyRegistry, propertyShare]);
  const market = m.contract("FixedPriceMarketDvP");

  const treasury = m.getParameter("treasury", "0x0000000000000000000000000000000000000000");
  const issuer = m.getParameter("issuer", "0x0000000000000000000000000000000000000000");

  m.call(kycRegistry, "setAllowed", [market, true]);
  m.call(kycRegistry, "setAllowed", [treasury, true]);
  m.call(kycRegistry, "setAllowed", [issuer, true]);

  return {
    kycRegistry,
    mockUsd,
    propertyRegistry,
    propertyShare,
    tokenizer,
    market,
  };
});

export default giwaSepoliaModule;
