// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import { ERC1155 } from "@openzeppelin/contracts/token/ERC1155/ERC1155.sol";
import { ERC1155Supply } from "@openzeppelin/contracts/token/ERC1155/extensions/ERC1155Supply.sol";
import { Ownable } from "@openzeppelin/contracts/access/Ownable.sol";
import { Strings } from "@openzeppelin/contracts/utils/Strings.sol";
import { KYCRegistry } from "./KYCRegistry.sol";

contract PropertyShare1155 is ERC1155, ERC1155Supply, Ownable {
    using Strings for uint256;

    uint256 public constant SHARE_SCALE = 1e18;

    KYCRegistry public immutable kyc;
    string private baseURI;

    constructor(string memory baseURI_, address kycAddress)
        ERC1155("")
        Ownable(msg.sender)
    {
        kyc = KYCRegistry(kycAddress);
        baseURI = baseURI_;
    }

    event MintBatchExecuted(address indexed to, uint256[] ids, uint256[] amounts);

    function uri(uint256 id) public view override returns (string memory) {
        return string.concat(baseURI, "/", id.toString(), ".json");
    }

    function setBaseURI(string memory nextBaseURI) external onlyOwner {
        baseURI = nextBaseURI;
    }

    function mintBatch(
        address to,
        uint256[] calldata ids,
        uint256[] calldata amounts
    ) external onlyOwner {
        _mintBatch(to, ids, amounts, "");
        emit MintBatchExecuted(to, ids, amounts);
    }

    function burn(
        address from,
        uint256 id,
        uint256 amount
    ) external {
        require(
            _msgSender() == from || _msgSender() == owner(),
            "PROPERTY_SHARE_1155: BURN_NOT_AUTHORIZED"
        );
        _burn(from, id, amount);
    }

    function safeTransferFrom(
        address from,
        address to,
        uint256 id,
        uint256 amount,
        bytes calldata data
    ) public override {
        _checkTransferAllowed(_msgSender(), from, to);
        super.safeTransferFrom(from, to, id, amount, data);
    }

    function safeBatchTransferFrom(
        address from,
        address to,
        uint256[] calldata ids,
        uint256[] calldata amounts,
        bytes calldata data
    ) public override {
        _checkTransferAllowed(_msgSender(), from, to);
        super.safeBatchTransferFrom(from, to, ids, amounts, data);
    }

    function _checkTransferAllowed(
        address operator,
        address from,
        address to
    ) internal view {
        if (from != address(0) && to != address(0)) {
            require(kyc.isAllowed(operator), "PROPERTY_SHARE_1155: OPERATOR_NOT_ALLOWED");
            require(kyc.isAllowed(from), "PROPERTY_SHARE_1155: FROM_NOT_ALLOWED");
            require(kyc.isAllowed(to), "PROPERTY_SHARE_1155: TO_NOT_ALLOWED");
        }
    }
}
