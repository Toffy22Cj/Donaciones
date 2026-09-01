// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract AnchorRegistry {
    event RootStored(bytes32 indexed root, address indexed sender, uint256 timestamp);

    // NOTA: En un entorno de produccion real, esta funcion tendria un modificador
    // como onlyOwner o un control de acceso basado en roles para asegurar que
    // solo las identidades autorizadas puedan anclar batches. Para este fixture
    // de tests de integracion, se relaja intencionalmente el acceso.
    function anchorRoot(bytes32 root) public {
        emit RootStored(root, msg.sender, block.timestamp);
    }
}
