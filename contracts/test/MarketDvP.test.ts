import { expect } from "chai";
import { ethers } from "hardhat";

const SHARE_SCALE = 10n ** 18n;

function bytes32(value: string) {
  return ethers.keccak256(ethers.toUtf8Bytes(value));
}

async function deployFixture() {
  const [owner, seller, buyer, treasury, issuer] = await ethers.getSigners();

  const KYCRegistry = await ethers.getContractFactory("KYCRegistry");
  const kyc = await KYCRegistry.connect(owner).deploy();
  await kyc.waitForDeployment();

  const MockUSD = await ethers.getContractFactory("MockUSD");
  const usd = await MockUSD.connect(owner).deploy();
  await usd.waitForDeployment();

  const PropertyRegistry = await ethers.getContractFactory("PropertyRegistry");
  const registry = await PropertyRegistry.connect(owner).deploy();
  await registry.waitForDeployment();

  const PropertyShare1155 = await ethers.getContractFactory("PropertyShare1155");
  const share = await PropertyShare1155.connect(owner).deploy(
    "https://example.com/metadata",
    await kyc.getAddress()
  );
  await share.waitForDeployment();

  const PropertyTokenizer = await ethers.getContractFactory("PropertyTokenizer");
  const tokenizer = await PropertyTokenizer.connect(owner).deploy(
    await registry.getAddress(),
    await share.getAddress()
  );
  await tokenizer.waitForDeployment();

  const FixedPriceMarketDvP = await ethers.getContractFactory("FixedPriceMarketDvP");
  const market = await FixedPriceMarketDvP.connect(owner).deploy();
  await market.waitForDeployment();

  await kyc.connect(owner).setAllowed(await owner.getAddress(), true);
  await kyc.connect(owner).setAllowed(await treasury.getAddress(), true);
  await kyc.connect(owner).setAllowed(await seller.getAddress(), true);
  await kyc.connect(owner).setAllowed(await buyer.getAddress(), true);
  await kyc.connect(owner).setAllowed(await market.getAddress(), true);
  await kyc.connect(owner).setAllowed(await issuer.getAddress(), true);

  return {
    owner,
    seller,
    buyer,
    treasury,
    issuer,
    kyc,
    usd,
    registry,
    share,
    tokenizer,
    market,
  };
}

describe("FixedPriceMarketDvP", () => {
  it("reverts listing and buy when market is not allowlisted", async () => {
    const { seller, buyer, kyc, usd, tokenizer, registry, share, market, owner } =
      await deployFixture();

    const classId = bytes32("ALLOWLIST-CLASS");
    await tokenizer.reserveAndRegisterClass(classId, bytes32("DOC"), 3, 0);
    const classInfo = await registry.getClass(classId);

    await tokenizer
      .connect(owner)
      .mintUnits(classId, 0, 1, await seller.getAddress());

    await kyc.connect(owner).setAllowed(await market.getAddress(), false);
    await expect(
      market
        .connect(seller)
        .list(
          await share.getAddress(),
          classInfo.baseTokenId,
          await usd.getAddress(),
          SHARE_SCALE,
          SHARE_SCALE
        )
    ).to.be.reverted;

    await kyc.connect(owner).setAllowed(await market.getAddress(), true);
    await usd.connect(owner).mint(await buyer.getAddress(), SHARE_SCALE);
    await usd.connect(buyer).approve(await market.getAddress(), SHARE_SCALE);
    await market
      .connect(seller)
      .list(
        await share.getAddress(),
        classInfo.baseTokenId,
        await usd.getAddress(),
        SHARE_SCALE,
        SHARE_SCALE
      );
    const listingId = (await market.nextListingId()) - 1n;

    await kyc.connect(owner).setAllowed(await market.getAddress(), false);
    await expect(market.connect(buyer).buy(listingId, SHARE_SCALE)).to.be.reverted;
  });

  it("keeps transfers atomic when ERC20 approval is insufficient", async () => {
    const { buyer, seller, usd, tokenizer, registry, share, market, owner } =
      await deployFixture();
    const classId = bytes32("ATOMIC-ALLOWANCE");

    await tokenizer.reserveAndRegisterClass(classId, bytes32("DOC"), 1, 0);
    const classInfo = await registry.getClass(classId);
    await tokenizer.connect(owner).mintUnits(classId, 0, 1, await seller.getAddress());

    await market
      .connect(seller)
      .list(
        await share.getAddress(),
        classInfo.baseTokenId,
        await usd.getAddress(),
        SHARE_SCALE,
        SHARE_SCALE
      );
    const listingId = (await market.nextListingId()) - 1n;

    await usd.connect(owner).mint(await buyer.getAddress(), SHARE_SCALE);
    await usd.connect(buyer).approve(await market.getAddress(), SHARE_SCALE - 1n);

    const sellerUsdBefore = await usd.balanceOf(await seller.getAddress());
    const buyerShareBefore = await share.balanceOf(await buyer.getAddress(), classInfo.baseTokenId);
    const marketShareBefore = await share.balanceOf(
      await market.getAddress(),
      classInfo.baseTokenId
    );

    await expect(market.connect(buyer).buy(listingId, SHARE_SCALE)).to.be.reverted;

    expect(await usd.balanceOf(await seller.getAddress())).to.equal(sellerUsdBefore);
    expect(await share.balanceOf(await buyer.getAddress(), classInfo.baseTokenId)).to.equal(
      buyerShareBefore
    );
    expect(await share.balanceOf(await market.getAddress(), classInfo.baseTokenId)).to.equal(
      marketShareBefore
    );
  });

  it("keeps transfers atomic when market has insufficient escrow", async () => {
    const { buyer, seller, usd, tokenizer, registry, share, market, owner } =
      await deployFixture();
    const classId = bytes32("ATOMIC-ESCROW");

    await tokenizer.reserveAndRegisterClass(classId, bytes32("DOC"), 1, 0);
    const classInfo = await registry.getClass(classId);
    await tokenizer.connect(owner).mintUnits(classId, 0, 1, await seller.getAddress());

    await market.connect(seller).list(
      await share.getAddress(),
      classInfo.baseTokenId,
      await usd.getAddress(),
      SHARE_SCALE,
      SHARE_SCALE
    );
    const listingId = (await market.nextListingId()) - 1n;

    await usd.connect(owner).mint(await buyer.getAddress(), SHARE_SCALE * 3n);
    await usd.connect(buyer).approve(await market.getAddress(), SHARE_SCALE * 3n);
    const sellerUsdBefore = await usd.balanceOf(await seller.getAddress());
    const marketShareBefore = await share.balanceOf(await market.getAddress(), classInfo.baseTokenId);

    await expect(market.connect(buyer).buy(listingId, SHARE_SCALE * 2n)).to.be.reverted;

    expect(await usd.balanceOf(await seller.getAddress())).to.equal(sellerUsdBefore);
    expect(await share.balanceOf(await market.getAddress(), classInfo.baseTokenId)).to.equal(
      marketShareBefore
    );
  });

  it("reduces remainingAmount on partial fills", async () => {
    const { buyer, seller, usd, tokenizer, registry, share, market, owner } =
      await deployFixture();
    const classId = bytes32("PARTIAL-FILL");

    await tokenizer.reserveAndRegisterClass(classId, bytes32("DOC"), 1, 0);
    const classInfo = await registry.getClass(classId);
    await tokenizer.connect(owner).mintUnits(classId, 0, 1, await seller.getAddress());

    await market.connect(seller).list(
      await share.getAddress(),
      classInfo.baseTokenId,
      await usd.getAddress(),
      SHARE_SCALE,
      SHARE_SCALE
    );
    const listingId = (await market.nextListingId()) - 1n;

    const buyAmount = SHARE_SCALE / 2n;
    await usd.connect(owner).mint(await buyer.getAddress(), buyAmount);
    await usd.connect(buyer).approve(await market.getAddress(), buyAmount);
    await market.connect(buyer).buy(listingId, buyAmount);

    const listing = await market.listings(listingId);
    expect(listing.status).to.equal(1);
    expect(listing.remainingAmount).to.equal(SHARE_SCALE - buyAmount);
    expect(await share.balanceOf(await buyer.getAddress(), classInfo.baseTokenId)).to.equal(
      buyAmount
    );
    expect(await usd.balanceOf(await seller.getAddress())).to.equal(buyAmount);
  });

  it("returns remaining shares to seller on cancel", async () => {
    const { seller, usd, tokenizer, registry, share, market, owner } =
      await deployFixture();
    const classId = bytes32("CANCEL");

    await tokenizer.reserveAndRegisterClass(classId, bytes32("DOC"), 1, 0);
    const classInfo = await registry.getClass(classId);
    await tokenizer.connect(owner).mintUnits(classId, 0, 1, await seller.getAddress());

    await market.connect(seller).list(
      await share.getAddress(),
      classInfo.baseTokenId,
      await usd.getAddress(),
      SHARE_SCALE,
      SHARE_SCALE
    );
    const listingId = (await market.nextListingId()) - 1n;

    await market.connect(seller).cancel(listingId);
    const listing = await market.listings(listingId);
    expect(listing.status).to.equal(2);
    expect(listing.remainingAmount).to.equal(0n);
    expect(await share.balanceOf(await seller.getAddress(), classInfo.baseTokenId)).to.equal(
      SHARE_SCALE
    );
    expect(await share.balanceOf(await market.getAddress(), classInfo.baseTokenId)).to.equal(0n);
  });

  it("allocates tokenizer class ranges and mintUnits emits expected unit IDs", async () => {
    const { owner, treasury, tokenizer, registry, share } = await deployFixture();

    const classId = bytes32("RANGE-CLASS");
    await tokenizer
      .connect(owner)
      .reserveAndRegisterClass(classId, bytes32("DOC-01"), 5, 1700000000);
    const classInfo = await registry.getClass(classId);
    expect(classInfo.baseTokenId).to.equal(1n);

    await tokenizer.connect(owner).mintUnits(classId, 1, 3, await treasury.getAddress());

    const mintedClassInfo = classInfo;
    const tokenIds = [
      mintedClassInfo.baseTokenId + 1n,
      mintedClassInfo.baseTokenId + 2n,
      mintedClassInfo.baseTokenId + 3n,
    ];
    const amounts = [await share.SHARE_SCALE(), await share.SHARE_SCALE(), await share.SHARE_SCALE()];
    const owners = [await treasury.getAddress(), await treasury.getAddress(), await treasury.getAddress()];
    const balances = await share.balanceOfBatch(owners, tokenIds);

    expect(classInfo.unitCount).to.equal(5);
    expect(balances[0]).to.equal(amounts[0]);
    expect(balances[1]).to.equal(amounts[1]);
    expect(balances[2]).to.equal(amounts[2]);
  });
});
