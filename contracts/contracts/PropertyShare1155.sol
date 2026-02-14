// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {ERC1155} from "@openzeppelin/contracts/token/ERC1155/ERC1155.sol";
import {ERC1155Supply} from "@openzeppelin/contracts/token/ERC1155/extensions/ERC1155Supply.sol";
import {Strings} from "@openzeppelin/contracts/utils/Strings.sol";

interface IKYCRegistry {
    function isAllowed(address user) external view returns (bool);
}

contract PropertyShare1155 is ERC1155, ERC1155Supply, Ownable {
    using Strings for uint256;

    uint256 public constant SHARE_SCALE = 1e18;

    IKYCRegistry public immutable kyc;

    string private _baseTokenURI;

    event BaseURIUpdated(string baseURI);

    error ZeroAddress();
    error OperatorNotAllowed(address operator);
    error SenderNotAllowed(address from);
    error ReceiverNotAllowed(address to);
    error UnauthorizedBurn(address caller);

    constructor(address kycRegistry, string memory baseURI) ERC1155("") Ownable(msg.sender) {
        if (kycRegistry == address(0)) {
            revert ZeroAddress();
        }

        kyc = IKYCRegistry(kycRegistry);
        _baseTokenURI = baseURI;
    }

    function setBaseURI(string calldata baseURI) external onlyOwner {
        _baseTokenURI = baseURI;
        emit BaseURIUpdated(baseURI);
    }

    function uri(uint256 id) public view override returns (string memory) {
        if (bytes(_baseTokenURI).length == 0) {
            return "";
        }

        return string.concat(_baseTokenURI, "/", id.toString(), ".json");
    }

    function mintBatch(address to, uint256[] calldata ids, uint256[] calldata amounts) external onlyOwner {
        _mintBatch(to, ids, amounts, "");
    }

    function burn(address from, uint256 id, uint256 amount) external {
        if (msg.sender != from && msg.sender != owner()) {
            revert UnauthorizedBurn(msg.sender);
        }

        _burn(from, id, amount);
    }

    function _update(
        address from,
        address to,
        uint256[] memory ids,
        uint256[] memory amounts
    ) internal override(ERC1155, ERC1155Supply) {
        if (from != address(0) && to != address(0)) {
            if (!kyc.isAllowed(msg.sender)) {
                revert OperatorNotAllowed(msg.sender);
            }
            if (!kyc.isAllowed(from)) {
                revert SenderNotAllowed(from);
            }
            if (!kyc.isAllowed(to)) {
                revert ReceiverNotAllowed(to);
            }
        }

        super._update(from, to, ids, amounts);
    }
}
