// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import { Ownable } from "@openzeppelin/contracts/access/Ownable.sol";

contract PropertyRegistry is Ownable {
    struct ClassInfo {
        bytes32 docHash;
        uint32 unitCount;
        uint256 baseTokenId;
        uint8 status;
        uint64 issuedAt;
    }

    mapping(bytes32 => ClassInfo) private classes;

    event ClassRegistered(
        bytes32 indexed classId,
        uint256 baseTokenId,
        uint32 unitCount,
        bytes32 docHash
    );
    event DocHashUpdated(bytes32 indexed classId, bytes32 oldHash, bytes32 newHash);
    event ClassStatusChanged(bytes32 indexed classId, uint8 status);

    constructor() Ownable(msg.sender) {}

    function registerClass(
        bytes32 classId,
        bytes32 docHash,
        uint32 unitCount,
        uint256 baseTokenId,
        uint64 issuedAt
    ) external onlyOwner {
        require(classId != bytes32(0), "PROPERTY_REGISTRY: ZERO_CLASS_ID");
        require(unitCount > 0, "PROPERTY_REGISTRY: ZERO_UNIT_COUNT");
        require(
            classes[classId].baseTokenId == 0,
            "PROPERTY_REGISTRY: CLASS_ALREADY_REGISTERED"
        );

        classes[classId] = ClassInfo({
            docHash: docHash,
            unitCount: unitCount,
            baseTokenId: baseTokenId,
            status: 1,
            issuedAt: issuedAt
        });

        emit ClassRegistered(classId, baseTokenId, unitCount, docHash);
    }

    function setStatus(bytes32 classId, uint8 status) external onlyOwner {
        require(classes[classId].baseTokenId != 0, "PROPERTY_REGISTRY: CLASS_NOT_FOUND");
        classes[classId].status = status;
        emit ClassStatusChanged(classId, status);
    }

    function updateDocHash(bytes32 classId, bytes32 newDocHash) external onlyOwner {
        require(classes[classId].baseTokenId != 0, "PROPERTY_REGISTRY: CLASS_NOT_FOUND");
        bytes32 oldHash = classes[classId].docHash;
        classes[classId].docHash = newDocHash;
        emit DocHashUpdated(classId, oldHash, newDocHash);
    }

    function getClass(bytes32 classId) external view returns (ClassInfo memory) {
        return classes[classId];
    }
}
