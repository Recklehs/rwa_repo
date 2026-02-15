// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";

contract KYCRegistry is Ownable {
    mapping(address => bool) private _allowed;

    event AllowedSet(address indexed user, bool allowed);

    constructor() Ownable(msg.sender) {}

    function setAllowed(address user, bool allowed) external onlyOwner {
        _allowed[user] = allowed;
        emit AllowedSet(user, allowed);
    }

    function batchSetAllowed(address[] calldata users, bool allowed) external onlyOwner {
        uint256 length = users.length;
        for (uint256 i = 0; i < length; ) {
            _allowed[users[i]] = allowed;
            emit AllowedSet(users[i], allowed);
            unchecked {
                ++i;
            }
        }
    }

    function isAllowed(address user) external view returns (bool) {
        return _allowed[user];
    }
}
