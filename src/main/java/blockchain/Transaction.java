package main.java.blockchain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import main.java.common.KeyManager;
import main.java.common.NodeRegistry;
import main.java.utils.DataUtils;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Objects;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {
    private static final Logger logger = LoggerFactory.getLogger(Transaction.class);

    private long transactionId;
    @JsonIgnore
    private Address senderAddress;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String functionSignature = null; // function signature of the transaction to be executed
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private TransactionType nativeOperation = null; // transaction to be executed outside smart contracts

    // argument fields (optional depending on the function to call)
    @JsonIgnore
    private Address ownerAddress = null; // e.g. transferFrom involves 3 addresses
    @JsonIgnore
    private Address receiverAddress = null;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double amount = null;
    @JsonIgnore
    private Wei weiAmount = null;

    @JsonIgnore
    @ToString.Exclude
    private byte[] signature;

    public Transaction(long transactionId, Address senderAddress, Address receiverAddress, String functionSignature, Double amount) {
        this.transactionId = transactionId;
        this.senderAddress = senderAddress;
        this.receiverAddress = receiverAddress;
        this.functionSignature = functionSignature;
        this.amount = amount;
    }

    public Transaction(long transactionId, Address senderAddress, Address fromAddress, Address toAddress, String functionSignature, Double amount) {
        this(transactionId, senderAddress, toAddress, functionSignature, amount);
        this.ownerAddress = fromAddress;
    }

    public Transaction(long transactionId, Address senderAddress, Address receiverAddress, TransactionType type, Wei amount) {
        this.transactionId = transactionId;
        this.senderAddress = senderAddress;
        this.receiverAddress = receiverAddress;
        this.nativeOperation = type;
        this.weiAmount = amount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Transaction t = (Transaction) o;
        return Objects.equals(transactionId, t.transactionId) && Objects.equals(getSignatureBase64(), t.getSignatureBase64());
    }

    /**
     * Converts the Transaction to a JSON string.
     */
    public String toJson() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            logger.error("Failed to convert transaction to JSON", e);
            return null;
        }
    }

    /**
     * Creates a Transaction object from a JSON string.
     */
    public static Transaction fromJson(String json) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(json, Transaction.class);
        } catch (JsonProcessingException e) {
            logger.error("Failed to convert JSON to Transaction", e);
            return null;
        }
    }

    /**
     * Verifies if transaction only has the sender address.
     */
    public boolean hasNoAddress() {
        return ownerAddress == null && receiverAddress == null;
    }

    /**
     * Verifies if transaction only has one address (besides the sender one).
     */
    public boolean hasOneAddress() {
        return ownerAddress == null && receiverAddress != null;
    }

    /**
     * Verifies if transaction has two address (besides the sender one).
     */
    public boolean hasTwoAddresses() {
        return ownerAddress != null && receiverAddress != null;
    }

    /**
     * Verify if transaction is correctly signed, is not repeated and is correctly formed.
     *
     * @param keyManager the key manager to verify the signature
     * @param blockchain to verify transaction signature and check replay attacks
     * @return true if transaction is valid, false otherwise
     */
    @JsonIgnore
    public boolean isValid(Blockchain blockchain, KeyManager keyManager) {
        // get the client that has the public key for the sender address
        NodeRegistry client = blockchain.getClients().get(senderAddress);
        if (client == null || signature == null) return false;

        // check if transaction is not a replay attack
        if (blockchain.checkRepeatedTransaction(getSignatureBase64())) return false;

        try {
            if (!keyManager.verifyTransaction(this, client)) {
                return false;
            }
        } catch (Exception e) {
            logger.error("Failed to verify signature for transaction from {}", senderAddress, e);
            return false;
        }

        // verify if function to be called exists
        TransactionType type = (functionSignature == null) ? nativeOperation : blockchain.getTransactionType(functionSignature);
        if (type == null) return false;

        // verify if arguments of the function are correct
        switch (type) {
            case ADD_TO_BLACKLIST:
            case IS_BLACKLISTED:
            case REMOVE_FROM_BLACKLIST:
            case BALANCE_OF:
                if (!hasOneAddress() || amount != null) return false;
                break;
            case NATIVE_BALANCE:
                if (!hasOneAddress() || weiAmount != null) return false;
                break;
            case APPROVE:
            case TRANSFER:
                if (!hasOneAddress() || amount == null) return false;
                break;
            case NATIVE_TRANSFER:
                if (!hasOneAddress() || weiAmount == null) return false;
                break;
            case ALLOWANCE:
                if (!hasTwoAddresses() || amount != null) return false;
                break;
            case TRANSFER_FROM:
                if (!hasTwoAddresses() || amount == null) return false;
                break;
            case TOTAL_SUPPLY:
                if (!hasNoAddress() || amount != null) return false;
                break;
            default:
                logger.error("Unknown transaction type: {}", type);
                return false;
        }

        return true;
    }

    @JsonProperty("senderAddress")
    public String getSenderAddressJson() {
        return this.senderAddress.toHexString();
    }

    @JsonProperty("senderAddress")
    public void setSenderAddressJson(String address) {
        this.senderAddress = Address.fromHexString(address);
    }

    @JsonProperty("receiverAddress")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getReceiverAddressJson() {
        return (receiverAddress == null) ? null : receiverAddress.toHexString();
    }

    @JsonProperty("receiverAddress")
    public void setReceiverAddressJson(String address) {
        this.receiverAddress = (address == null) ? null : Address.fromHexString(address);
    }

    @JsonProperty("ownerAddress")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getOwnerAddressJson() {
        return (ownerAddress == null) ? null : ownerAddress.toHexString();
    }

    @JsonProperty("ownerAddress")
    public void setOwnerAddressJson(String address) {
        this.ownerAddress = (address == null) ? null : Address.fromHexString(address);
    }

    @JsonProperty("weiAmount")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getBalanceJson() {
        return (weiAmount == null) ? null : DataUtils.convertAmountToBigDecimalString(weiAmount);
    }

    @JsonProperty("weiAmount")
    public void setBalanceJson(String amount) {
        this.weiAmount = (amount == null) ? null : DataUtils.convertAmountToWei(amount);
    }

    /**
     * Retrieves the properties of the transaction to be signed.
     *
     * @return a byte array representing the properties to be signed
     */
    @JsonIgnore
    public byte[] getPropertiesToSign() {
        try {
            String propertiesString = transactionId + ",";
            propertiesString += (functionSignature == null ? nativeOperation : functionSignature);
            propertiesString += (ownerAddress == null) ? "" : "," + ownerAddress.toHexString();
            propertiesString += (receiverAddress == null) ? "" : "," + receiverAddress.toHexString();
            propertiesString += (amount == null) ? "" : "," + amount;
            return propertiesString.getBytes();
        } catch (Exception e) {
            logger.error("Failed to get properties to sign transaction", e);
            return null;
        }
    }

    /**
     * Retrieves the signature as a Base64 encoded string.
     *
     * @return the Base64 encoded string representation of the signature
     */
    @JsonProperty("signature")
    public String getSignatureBase64() {
        return Base64.getEncoder().encodeToString(signature);
    }

    /**
     * Sets the signature from a Base64 encoded string.
     *
     * @param signatureBase64 the Base64 encoded string representation of the signature
     */
    @JsonProperty("signature")
    public void setSignatureBase64(String signatureBase64) {
        this.signature = Base64.getDecoder().decode(signatureBase64);
    }
}
