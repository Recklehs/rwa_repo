// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import { Ownable } from "@openzeppelin/contracts/access/Ownable.sol";

contract KYCRegistry is Ownable {
    constructor() Ownable(msg.sender) {}

    mapping(address => bool) private allowed;

    event AllowedSet(address indexed user, bool allowed);

    function setAllowed(address user, bool isAllowed) external onlyOwner {
        allowed[user] = isAllowed;
        emit AllowedSet(user, isAllowed);
    }

    function batchSetAllowed(address[] calldata users, bool isAllowed) external onlyOwner {
        for (uint256 i = 0; i < users.length; i++) {
            allowed[users[i]] = isAllowed;
            emit AllowedSet(users[i], isAllowed);
        }
    }

    function isAllowed(address user) external view returns (bool) {
        return allowed[user];
    }
}
