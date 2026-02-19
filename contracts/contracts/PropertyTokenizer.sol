// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import { Ownable } from "@openzeppelin/contracts/access/Ownable.sol";
import { PropertyRegistry } from "./PropertyRegistry.sol";
import { PropertyShare1155 } from "./PropertyShare1155.sol";

contract PropertyTokenizer is Ownable {
    PropertyRegistry public immutable registry;
    PropertyShare1155 public immutable share;

    uint256 public nextBaseTokenId;

    event ClassReserved(
        bytes32 indexed classId,
        uint256 baseTokenId,
        uint32 unitCount
    );
    event UnitsMinted(
        bytes32 indexed classId,
        uint32 startOffset,
        uint32 count,
        address treasury
    );

    constructor(PropertyRegistry registry_, PropertyShare1155 share_) Ownable(msg.sender) {
        registry = registry_;
        share = share_;
        nextBaseTokenId = 1;
    }

    function reserveAndRegisterClass(
        bytes32 classId,
        bytes32 docHash,
        uint32 unitCount,
        uint64 issuedAt
    ) external onlyOwner returns (uint256 baseTokenId) {
        require(unitCount > 0, "TOKENIZER: ZERO_UNITS");

        baseTokenId = nextBaseTokenId;
        nextBaseTokenId += uint256(unitCount);

        registry.registerClass(classId, docHash, unitCount, baseTokenId, issuedAt);
        emit ClassReserved(classId, baseTokenId, unitCount);
    }

    function mintUnits(
        bytes32 classId,
        uint32 startOffset,
        uint32 count,
        address treasury
    ) external onlyOwner {
        PropertyRegistry.ClassInfo memory classInfo = registry.getClass(classId);
        require(classInfo.baseTokenId != 0, "TOKENIZER: CLASS_NOT_FOUND");
        require(count > 0, "TOKENIZER: ZERO_MINT_COUNT");
        require(uint256(startOffset) + uint256(count) <= classInfo.unitCount, "TOKENIZER: MINT_RANGE_EXCEEDED");

        uint256[] memory ids = new uint256[](count);
        uint256[] memory amounts = new uint256[](count);

        for (uint256 i = 0; i < uint256(count); i++) {
            ids[i] = classInfo.baseTokenId + uint256(startOffset) + i;
            amounts[i] = share.SHARE_SCALE();
        }

        share.mintBatch(treasury, ids, amounts);
        emit UnitsMinted(classId, startOffset, count, treasury);
    }
}
