// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import { IERC20 } from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import { IERC1155 } from "@openzeppelin/contracts/token/ERC1155/IERC1155.sol";
import { SafeERC20 } from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import { ReentrancyGuard } from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import { Math } from "@openzeppelin/contracts/utils/math/Math.sol";
import { ERC1155Holder } from "@openzeppelin/contracts/token/ERC1155/utils/ERC1155Holder.sol";

contract FixedPriceMarketDvP is ReentrancyGuard, ERC1155Holder {
    using SafeERC20 for IERC20;

    uint8 internal constant STATUS_ACTIVE = 1;
    uint8 internal constant STATUS_CANCELLED = 2;
    uint8 internal constant STATUS_FILLED = 3;
    uint256 internal constant SHARE_SCALE = 1e18;

    struct Listing {
        address seller;
        address shareToken;
        uint256 tokenId;
        address payToken;
        uint256 unitPrice;
        uint256 totalAmount;
        uint256 remainingAmount;
        uint8 status;
    }

    uint256 public nextListingId = 1;
    mapping(uint256 => Listing) public listings;

    event Listed(
        uint256 indexed listingId,
        address indexed seller,
        address indexed shareToken,
        uint256 tokenId,
        address payToken,
        uint256 amount,
        uint256 unitPrice
    );
    event Bought(
        uint256 indexed listingId,
        address indexed buyer,
        address indexed seller,
        address shareToken,
        uint256 tokenId,
        address payToken,
        uint256 amount,
        uint256 unitPrice,
        uint256 cost
    );
    event Cancelled(uint256 indexed listingId);

    function list(
        address shareToken,
        uint256 tokenId,
        address payToken,
        uint256 amount,
        uint256 unitPrice
    ) external nonReentrant returns (uint256 listingId) {
        require(amount > 0, "MARKET: AMOUNT_ZERO");
        require(unitPrice > 0, "MARKET: UNIT_PRICE_ZERO");

        listingId = nextListingId;
        nextListingId = nextListingId + 1;

        listings[listingId] = Listing({
            seller: msg.sender,
            shareToken: shareToken,
            tokenId: tokenId,
            payToken: payToken,
            unitPrice: unitPrice,
            totalAmount: amount,
            remainingAmount: amount,
            status: STATUS_ACTIVE
        });

        IERC1155(shareToken).safeTransferFrom(msg.sender, address(this), tokenId, amount, "");

        emit Listed(listingId, msg.sender, shareToken, tokenId, payToken, amount, unitPrice);
    }

    function buy(uint256 listingId, uint256 amount) external nonReentrant {
        Listing storage listing = listings[listingId];
        require(listing.status == STATUS_ACTIVE, "MARKET: LISTING_NOT_ACTIVE");
        require(amount > 0, "MARKET: AMOUNT_ZERO");
        require(listing.remainingAmount >= amount, "MARKET: INSUFFICIENT_REMAINING");

        uint256 cost = Math.mulDiv(amount, listing.unitPrice, SHARE_SCALE);
        listing.remainingAmount -= amount;
        if (listing.remainingAmount == 0) {
            listing.status = STATUS_FILLED;
        }

        IERC20(listing.payToken).safeTransferFrom(msg.sender, listing.seller, cost);
        IERC1155(listing.shareToken).safeTransferFrom(
            address(this),
            msg.sender,
            listing.tokenId,
            amount,
            ""
        );

        emit Bought(
            listingId,
            msg.sender,
            listing.seller,
            listing.shareToken,
            listing.tokenId,
            listing.payToken,
            amount,
            listing.unitPrice,
            cost
        );
    }

    function cancel(uint256 listingId) external nonReentrant {
        Listing storage listing = listings[listingId];
        require(listing.seller == msg.sender, "MARKET: ONLY_SELLER");
        require(listing.status == STATUS_ACTIVE, "MARKET: LISTING_NOT_ACTIVE");

        uint256 remainingAmount = listing.remainingAmount;
        listing.status = STATUS_CANCELLED;
        listing.remainingAmount = 0;

        if (remainingAmount > 0) {
            IERC1155(listing.shareToken).safeTransferFrom(
                address(this),
                listing.seller,
                listing.tokenId,
                remainingAmount,
                ""
            );
        }

        emit Cancelled(listingId);
    }
}
