# Solidity Test Fixture

This directory contains `AnchorRegistry.sol`, a minimal Solidity fixture used for integration tests of the Web3j Blockchain Anchor Adapter.

## Updating the Contract
The `.abi` and `.bin` artifacts inside the `build/` directory are the source of truth for generating the Web3j Java wrapper (`AnchorRegistry.java`) during the `generate-test-sources` Maven phase. This circumvents upstream bugs with the `web3j-maven-plugin` attempting to fetch `solc` automatically from non-existent GitHub endpoints.

If you modify `AnchorRegistry.sol`, you **MUST** recompile it to update the `.abi` and `.bin` artifacts. 

You can do this deterministically via Docker using the official Solidity compiler image:

```bash
cd crypto
docker run --rm -v $(pwd)/src/test/resources/solidity:/solidity ethereum/solc:0.8.0 -o /solidity/build --abi --bin --overwrite /solidity/AnchorRegistry.sol
```

After updating the `.abi` and `.bin` files, run a Maven build (`mvn clean generate-test-sources -pl crypto`) to regenerate the Java wrapper.
