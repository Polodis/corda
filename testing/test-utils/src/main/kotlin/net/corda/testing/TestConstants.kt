@file:JvmName("TestConstants")

package net.corda.testing

import net.corda.core.contracts.Command
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.entropyToKeyPair
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.internal.toX509CertHolder
import net.corda.core.internal.x500Name
import net.corda.nodeapi.internal.crypto.*
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralSubtree
import org.bouncycastle.asn1.x509.NameConstraints
import org.bouncycastle.cert.X509CertificateHolder
import java.math.BigInteger
import java.security.KeyPair
import java.security.PublicKey
import java.security.Security
import java.time.Instant

// A dummy time at which we will be pretending test transactions are created.
val TEST_TX_TIME: Instant get() = Instant.parse("2015-04-17T12:00:00.00Z")
private val DUMMY_NOTARY_KEY: KeyPair by lazy { entropyToKeyPair(BigInteger.valueOf(20)) }
val DUMMY_NOTARY_NAME = CordaX500Name("Notary Service", "Zurich", "CH")
val DUMMY_NOTARY: Party get() = Party(DUMMY_NOTARY_NAME, DUMMY_NOTARY_KEY.public)
val DUMMY_BANK_A_KEY: KeyPair by lazy { entropyToKeyPair(BigInteger.valueOf(40)) }
/** Dummy bank identity for tests and simulations */
val DUMMY_BANK_A: Party get() = Party(CordaX500Name(organisation = "Bank A", locality = "London", country = "GB"), DUMMY_BANK_A_KEY.public)

val DUMMY_BANK_B_KEY: KeyPair by lazy { entropyToKeyPair(BigInteger.valueOf(50)) }
/** Dummy bank identity for tests and simulations */
val DUMMY_BANK_B: Party get() = Party(CordaX500Name(organisation = "Bank B", locality = "New York", country = "US"), DUMMY_BANK_B_KEY.public)

val DUMMY_BANK_C_KEY: KeyPair by lazy { entropyToKeyPair(BigInteger.valueOf(60)) }
/** Dummy bank identity for tests and simulations */
val DUMMY_BANK_C: Party get() = Party(CordaX500Name(organisation = "Bank C", locality = "Tokyo", country = "JP"), DUMMY_BANK_C_KEY.public)

val ALICE_KEY: KeyPair by lazy { entropyToKeyPair(BigInteger.valueOf(70)) }
/** Dummy individual identity for tests and simulations */
val ALICE_NAME = CordaX500Name(organisation = "Alice Corp", locality = "Madrid", country = "ES")
val ALICE: Party get() = Party(ALICE_NAME, ALICE_KEY.public)

val BOB_KEY: KeyPair by lazy { entropyToKeyPair(BigInteger.valueOf(80)) }
/** Dummy individual identity for tests and simulations */
val BOB_NAME = CordaX500Name(organisation = "Bob Plc", locality = "Rome", country = "IT")
val BOB: Party get() = Party(BOB_NAME, BOB_KEY.public)
val DEV_CA: CertificateAndKeyPair by lazy {
    // TODO: Should be identity scheme
    val caKeyStore = loadKeyStore(ClassLoader.getSystemResourceAsStream("net/corda/node/internal/certificates/cordadevcakeys.jks"), "cordacadevpass")
    caKeyStore.getCertificateAndKeyPair(X509Utilities.CORDA_INTERMEDIATE_CA, "cordacadevkeypass")
}
val DEV_TRUST_ROOT: X509CertificateHolder by lazy {
    // TODO: Should be identity scheme
    val caKeyStore = loadKeyStore(ClassLoader.getSystemResourceAsStream("net/corda/node/internal/certificates/cordadevcakeys.jks"), "cordacadevpass")
    caKeyStore.getCertificateChain(X509Utilities.CORDA_INTERMEDIATE_CA).last().toX509CertHolder()
}

fun dummyCommand(vararg signers: PublicKey = arrayOf(generateKeyPair().public)) = Command<TypeOnlyCommandData>(DummyCommandData, signers.toList())

object DummyCommandData : TypeOnlyCommandData()
