/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package remoteprovisioning;

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import net.i2p.crypto.eddsa.EdDSASecurityProvider;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.AsymmetricCipherKeyPairGenerator;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator;
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import COSE.AlgorithmID;
import COSE.Attribute;
import COSE.CoseException;
import COSE.HeaderKeys;
import COSE.KeyKeys;
import COSE.Message;
import COSE.MessageTag;
import COSE.OneKey;
import COSE.Sign1Message;

import java.util.Arrays;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.*;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;

/*
 * This class is a cryptographic utility class that implements a variety of functions used by other
 * portions of this library.
 */
public class CryptoUtil {

    // These curve values are taken from Table 22 in RFC 8152.
    private static final int X25519 = 4;
    private static final int ED25519 = 6;

    /*
     * Generates the shared public key material from ECDH used to derive the sender and receiver AES
     * keys.
     *
     * @return byte[] the shared key material for use in a KDF
     */
    private static byte[] deriveSharedKeyMaterial(X25519PrivateKeyParameters priv,
                                                  X25519PublicKeyParameters pub)
                                                  throws NoSuchAlgorithmException,
                                                         InvalidKeyException {
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(priv);
        byte[] secret = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(pub, secret, 0 /* offset */);
        return secret;
    }

    /*
     * Derives the sender key for an ECDH key agreement. The sender key is generated by including
     * the hash of the caller's public key first, followed by the hash of the other party's public
     * key. This is done so that the two sides don't have to agree to partition the
     * nonce space as they would have to do with only a single AES key derived from the ECDH key
     * exchange. It also has the added benefit of not allowing messages to be replayed to the party
     * that sent them.
     *
     * @param keyPair the caller's X25519 public/private key pair
     *
     * @param otherPub the public key of the other party's X25519 key pair
     *
     * @return byte[] the AES-256 sender key
     */
    public static byte[] deriveSharedKeySend(AsymmetricCipherKeyPair keyPair,
                                             X25519PublicKeyParameters otherPub)
                                             throws CryptoException {
        try {
            X25519PublicKeyParameters pub = (X25519PublicKeyParameters) keyPair.getPublic();
            byte[] context =
                CborUtil.buildKdfContext(
                    CborUtil.buildParty("device", pub.getEncoded()),
                    CborUtil.buildParty("server", otherPub.getEncoded()));
            byte[] keyMaterial = deriveSharedKeyMaterial(
                                    (X25519PrivateKeyParameters) keyPair.getPrivate(), otherPub);
            return computeHkdf("HmacSha256", keyMaterial, null /* salt */, context, 16 /* size */);
        } catch (NoSuchAlgorithmException e) {
            throw new CryptoException("Missing ECDH algorithm provider",
                                      e, CryptoException.NO_SUCH_ALGORITHM);
        } catch (InvalidKeyException e) {
            throw new CryptoException("Derived ECDH key is malformed",
                                      e, CryptoException.MALFORMED_KEY);
        }
    }

    /*
     * Derives the receiver key for an ECDH key agreement. The receiver key is generated by
     * including the hash of the other party's public key first, followed by the caller's public
     * key. This is done so that the two sides don't have to agree to partition the
     * nonce space as they would have to do with only a single AES key derived from the ECDH key
     * exchange. It also has the added benefit of not allowing the messages to be replayed to the
     * party that sent them.
     *
     * @param keyPair the caller's X25519 public/private key pair
     *
     * @param otherPub the public key of the other party's X25519 key pair
     *
     * @return byte[] the AES-256 receiver key
     */
    public static byte[] deriveSharedKeyReceive(AsymmetricCipherKeyPair keyPair,
                                                X25519PublicKeyParameters otherPub)
                                                throws CryptoException {
        try {
            X25519PublicKeyParameters pub = (X25519PublicKeyParameters) keyPair.getPublic();
            byte[] context =
                CborUtil.buildKdfContext(
                    CborUtil.buildParty("device", otherPub.getEncoded()),
                    CborUtil.buildParty("server", pub.getEncoded()));
            byte[] keyMaterial = deriveSharedKeyMaterial(
                                    (X25519PrivateKeyParameters) keyPair.getPrivate(), otherPub);
            return computeHkdf("HmacSha256", keyMaterial, null /* salt */, context, 16 /* size */);
        } catch (NoSuchAlgorithmException e) {
            throw new CryptoException("Missing ECDH algorithm provider",
                                      e, CryptoException.NO_SUCH_ALGORITHM);
        } catch (InvalidKeyException e) {
            throw new CryptoException("Derived ECDH key is malformed",
                                      e, CryptoException.MALFORMED_KEY);
        }
    }

    /*
     * Generates an X25519 ECDH key pair.
     *
     * @return KeyPair an X25519 key pair
     */
    public static AsymmetricCipherKeyPair genX25519() {
        AsymmetricCipherKeyPairGenerator kpGen = new X25519KeyPairGenerator();
        kpGen.init(new X25519KeyGenerationParameters(new SecureRandom()));

        return kpGen.generateKeyPair();
    }

    /*
     * Convert an EdDSAPrivateKey to a COSE Key object
     */
    private static OneKey eddsaToOneKey(EdDSAPrivateKey priv) {
        byte[] rgbD = priv.getSeed();
        OneKey key = new OneKey();
        key.add(KeyKeys.KeyType, KeyKeys.KeyType_OKP);
        key.add(KeyKeys.Algorithm, AlgorithmID.EDDSA.AsCBOR());
        key.add(KeyKeys.OKP_Curve, KeyKeys.OKP_Ed25519);
        key.add(KeyKeys.OKP_D, CBORObject.FromObject(rgbD));
        return key;
    }

    public static byte[] digestX25519(X25519PublicKeyParameters pubKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(pubKey.getEncoded());
        } catch (Exception e) {
            return null;
        }
    }

    /*
     * Convert an X25519 public key to a COSE Key object
     */
    public static OneKey x25519ToOneKey(X25519PublicKeyParameters pubKey) {
        OneKey key = new OneKey();
        key.add(KeyKeys.KeyType, KeyKeys.KeyType_OKP);
        key.add(KeyKeys.KeyId, CBORObject.FromObject(digestX25519(pubKey)));
        key.add(KeyKeys.Algorithm, AlgorithmID.ECDH_ES_HKDF_256.AsCBOR());
        key.add(KeyKeys.OKP_Curve, KeyKeys.OKP_X25519);
        key.add(KeyKeys.OKP_X, CBORObject.FromObject(pubKey.getEncoded()));
        return key;
    }

    public static PublicKey oneKeyToP256PublicKey(OneKey key)
            throws CborException, CryptoException {
        if (!key.get(KeyKeys.KeyType).equals(KeyKeys.KeyType_EC2)) {
            throw new CborException("Key has unexpected key type (kty)",
                                        KeyKeys.KeyType_EC2.AsInt32(),
                                        key.get(KeyKeys.KeyType).AsInt32(),
                                        CborException.INCORRECT_COSE_TYPE);
        }
        if (!key.get(KeyKeys.Algorithm).equals(AlgorithmID.ECDSA_256.AsCBOR())) {
            throw new CborException("Key has unexpected algorithm",
                                        AlgorithmID.ECDSA_256.AsCBOR().AsInt32(),
                                        key.get(KeyKeys.Algorithm).AsInt32(),
                                        CborException.INCORRECT_COSE_TYPE);
        }
        if (!key.get(KeyKeys.EC2_Curve).equals(KeyKeys.EC2_P256)) {
            throw new CborException("Key has unexpected curve",
                                        KeyKeys.EC2_P256.AsInt32(),
                                        key.get(KeyKeys.EC2_Curve).AsInt32(),
                                        CborException.INCORRECT_COSE_TYPE);
        }
        try {
            BigInteger x = new BigInteger(key.get(KeyKeys.EC2_X).GetByteString());
            BigInteger y = new BigInteger(key.get(KeyKeys.EC2_Y).GetByteString());
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec ecParameters = parameters.getParameterSpec(ECParameterSpec.class);
            ECPoint point = new ECPoint(x, y);
            ECPublicKeySpec keySpec = new ECPublicKeySpec(point, ecParameters);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException
                 | InvalidParameterSpecException
                 | InvalidKeySpecException e) {
            throw new CryptoException("No support for P256.", e, CryptoException.NO_SUCH_ALGORITHM);
        }
    }

    /*
     * Convert an X25519 public key to a byte encoding
     */
    public static byte[] cborEncodeX25519PubKey(X25519PublicKeyParameters pubKey) {
        return x25519ToOneKey(pubKey).EncodeToBytes();
    }

    /*
     * Encrypt {@code content} with {@code aad} as the associated authenticated data using AES-GCM
     */
    public static byte[] encrypt(byte[] content, byte[] aad, byte[] key, byte[] iv)
            throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                        new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(content);
        } catch (Exception e) {
            throw new CryptoException("Encryption failure",
                e, CryptoException.ENCRYPTION_FAILURE);
        }
    }

    /*
     * Decrypt {@code encryptedContent} with {@code aad} as the associated authenticated data using
     * AES-GCM
     */
    public static byte[] decrypt(byte[] encryptedContent, byte[] aad, byte[] key, byte[] iv)
            throws CryptoException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad);

            return cipher.doFinal(encryptedContent);
        } catch (Exception e) {
            throw new CryptoException("Decryption failure",
                e, CryptoException.DECRYPTION_FAILURE);
        }
    }

    /*
     * Create a COSE_Sign1 message object with one Ed25519 signing key and one X25519 public
     * encryption key.
     *
     * @param signingKey the key used to sign the COSE_Sign1 certificate
     *
     * @param pubKey the public key that will be signed represented as a standard java PublicKey
     *
     * @return CBORObject a CBORObject representing a COSE_Sign1 message
     */
    public static CBORObject createCertificateEd25519(OneKey signingKey,
                                                      X25519PublicKeyParameters pubKey)
                                                      throws CryptoException {
        try {
            Security.addProvider(new EdDSASecurityProvider());
            Sign1Message cert = new Sign1Message();
            cert.addAttribute(
                HeaderKeys.Algorithm, AlgorithmID.EDDSA.AsCBOR(), Attribute.PROTECTED);
            cert.SetContent(cborEncodeX25519PubKey(pubKey));
            cert.sign(signingKey);
            return cert.EncodeToCBORObject();
        } catch (CoseException e) {
            throw new CryptoException(
                "Failed to sign certificate", e, CryptoException.SIGNING_FAILURE);
        }
    }

    /*
     * Create a COSE_Sign1 message object with two Ed25519 signing keys.
     *
     * @param signingKey the key used to sign the COSE_Sign1 certificate
     *
     * @param pubKey the public key that will be signed represented as a COSE_Key
     *
     * @return CBORObject a CBORObject representing a COSE_Sign1 message
     */ 
    public static CBORObject createCertificateEd25519(OneKey signingKey, OneKey pubKey)
            throws CryptoException {
        try {
            Security.addProvider(new EdDSASecurityProvider());
            Sign1Message cert = new Sign1Message();
            cert.addAttribute(
                HeaderKeys.Algorithm, AlgorithmID.EDDSA.AsCBOR(), Attribute.PROTECTED);
            cert.SetContent(pubKey.PublicKey().EncodeToBytes());
            cert.sign(signingKey);
            return cert.EncodeToCBORObject();
        } catch (CoseException e) {
            throw new CryptoException(
                "Failed to sign certificate", e, CryptoException.SIGNING_FAILURE);
        }
    }

    /*
     * Checks that the signature on the {@code certToVerifyCbor} certificate is verified with the
     * public key from the corresponding {@code verifyingCertCbor} certificate.
     *
     * @return boolean true if verification succeeds, false otherwise.
     */
    public static boolean verifyCert(CBORObject verifyingCertCbor, CBORObject certToVerifyCbor)
                throws CborException, CryptoException {
        return verifyCert(verifyingCertCbor, certToVerifyCbor, null);
    }

    /*
     * Checks that the signature on the {@code certToVerifyCbor} certificate is verified with the
     * public key from the corresponding {@code verifyingCertCbor} certificate.
     *
     * @param expectedKey an optional parameter that can be used to see if the key contained in the
     *                    content field of certToVerifyCbor is what was expected
     * @return boolean true if verification succeeds, false otherwise.
     */
    public static boolean verifyCert(CBORObject verifyingCertCbor,
                                     CBORObject certToVerifyCbor,
                                     OneKey expectedKey)
                throws CborException, CryptoException {
        Sign1Message certToVerify = new Sign1Message();
        Sign1Message verifyingCert = new Sign1Message();
        OneKey verifyingKey;
        try {
            certToVerify = (Sign1Message) Message.DecodeFromBytes(
                certToVerifyCbor.EncodeToBytes(), MessageTag.Sign1);
            verifyingCert = (Sign1Message) Message.DecodeFromBytes(
                verifyingCertCbor.EncodeToBytes(), MessageTag.Sign1);
            verifyingKey = new OneKey(CBORObject.DecodeFromBytes(verifyingCert.GetContent()));
        } catch (CoseException e) {
            throw new CborException("Failed to decode certificates or their content",
                                    e, CborException.DESERIALIZATION_ERROR);
        }

        try {
            if (!certToVerify.validate(verifyingKey)) {
                return false;
            }
        } catch (CoseException e) {
            throw new CryptoException("Failed to validate certificate chain",
                                      e, CryptoException.VERIFICATION_FAILURE);
        }

        try {
            if (expectedKey != null) {
                OneKey verifiedKey =
                    new OneKey(CBORObject.DecodeFromBytes(certToVerify.GetContent()));
                if (!Arrays.equals((byte[]) verifiedKey.get(KeyKeys.OKP_X).GetByteString(),
                                   (byte[]) expectedKey.get(KeyKeys.OKP_X).GetByteString())) {
                    throw new CryptoException("Key in certificate does not match the expected key",
                                              CryptoException.VERIFICATION_FAILURE);
                }
            }
        } catch (CoseException e) {
            throw new CborException("Failed to decode certificates or their content",
                                    e, CborException.DESERIALIZATION_ERROR);
        }
        return true;
    }

    /*
     * Retrieves a key signed by a certificate that exists within a COSE Sign1Message object after
     * validating that the key properties recorded in the COSE object match what is expected.
     *
     * @param certObj the COSE Sign1Message structure
     *
     * @return X25519PublicKeyParameters the X25519 key that was in the Sign1Message content field
     */
    public static X25519PublicKeyParameters getX25519PublicKeyFromCert(CBORObject certObj)
            throws CborException, CryptoException {
        try {
            Sign1Message cert = (Sign1Message) Message.DecodeFromBytes(
                certObj.EncodeToBytes(), MessageTag.Sign1);
            CBORObject content = CBORObject.DecodeFromBytes(cert.GetContent());
            if (content.get(KeyKeys.OKP_Curve.AsCBOR()).getType() != CBORType.Integer) {
                throw new CborException("Curve field does not have expected type",
                                        CBORType.Integer,
                                        content.get(KeyKeys.OKP_Curve.AsCBOR()).getType(),
                                        CborException.TYPE_MISMATCH);
            }
            CBORObject keyType = content.get(KeyKeys.KeyType.AsCBOR());
            if (keyType.getType() != CBORType.Integer) {
                throw new CborException("Key type field does not have expected type",
                                        CBORType.Integer,
                                        keyType.getType(),
                                        CborException.TYPE_MISMATCH);
            }
            if (keyType.AsInt32() != KeyKeys.KeyType_OKP.AsInt32()) {
                throw new CborException("Key has unexpected key type (kty)",
                                        KeyKeys.KeyType_OKP.AsInt32(),
                                        keyType.AsInt32(),
                                        CborException.INCORRECT_COSE_TYPE);
            }
            int curve = content.get(KeyKeys.OKP_Curve.AsCBOR()).AsInt32();
            CBORObject algorithm = content.get(KeyKeys.Algorithm.AsCBOR());
            if (algorithm.getType() != CBORType.Integer) {
                throw new CborException("Algorithm has unexpected CBOR type",
                                        CBORType.Integer,
                                        algorithm.getType(),
                                        CborException.TYPE_MISMATCH);
            }
            if (curve != X25519 ||
                    algorithm.AsInt32() != AlgorithmID.ECDH_ES_HKDF_256.AsCBOR().AsInt32()) {
                throw new CborException("Algorithm does not match the curve",
                                        AlgorithmID.ECDH_ES_HKDF_256.AsCBOR().AsInt32(),
                                        algorithm.AsInt32(),
                                        CborException.INCORRECT_COSE_TYPE);
            }
            return getX25519PublicKeyFromCert(cert);
        } catch (CoseException e) {
            throw new CborException("Failed to decode certificate",
                                    e, CborException.DESERIALIZATION_ERROR);
        }
    }

    /*
     * Retrieves a key signed by a certificate that exists within a COSE Sign1Message object after
     * validating that the key properties recorded in the COSE object match what is expected.
     *
     * @param certObj the COSE Sign1Message structure
     *
     * @return PublicKey the EdDSA key that was in the Sign1Message content field
     */
    public static PublicKey getEd25519PublicKeyFromCert(CBORObject certObj)
            throws CborException, CryptoException {
        try {
            Sign1Message cert = (Sign1Message) Message.DecodeFromBytes(
                certObj.EncodeToBytes(), MessageTag.Sign1);
            CBORObject content = CBORObject.DecodeFromBytes(cert.GetContent());
            if (content.get(KeyKeys.OKP_Curve.AsCBOR()).getType() != CBORType.Integer) {
                throw new CborException("Curve field does not have expected type",
                                        CBORType.Integer,
                                        content.get(KeyKeys.OKP_Curve.AsCBOR()).getType(),
                                        CborException.TYPE_MISMATCH);
            }
            CBORObject keyType = content.get(KeyKeys.KeyType.AsCBOR());
            if (keyType.getType() != CBORType.Integer) {
                throw new CborException("Key type field does not have expected type",
                                        CBORType.Integer,
                                        keyType.getType(),
                                        CborException.TYPE_MISMATCH);
            }
            if (keyType.AsInt32() != KeyKeys.KeyType_OKP.AsInt32()) {
                throw new CborException("Key has unexpected key type (kty)",
                                        KeyKeys.KeyType_OKP.AsInt32(),
                                        keyType.AsInt32(),
                                        CborException.INCORRECT_COSE_TYPE);
            }
            int curve = content.get(KeyKeys.OKP_Curve.AsCBOR()).AsInt32();
            CBORObject algorithm = content.get(KeyKeys.Algorithm.AsCBOR());
            if (algorithm.getType() != CBORType.Integer) {
                throw new CborException("Algorithm has unexpected CBOR type",
                                        CBORType.Integer,
                                        algorithm.getType(),
                                        CborException.TYPE_MISMATCH);
            }
            if (curve != ED25519 ||
                    algorithm.AsInt32() != AlgorithmID.EDDSA.AsCBOR().AsInt32()) {
                throw new CborException("Algorithm does not match the curve",
                                        AlgorithmID.EDDSA.AsCBOR().AsInt32(),
                                        algorithm.AsInt32(),
                                        CborException.INCORRECT_COSE_TYPE);
            }
            return getEd25519PublicKeyFromCert(cert);
        } catch (CoseException e) {
            throw new CborException("Failed to decode certificate",
                                    e, CborException.DESERIALIZATION_ERROR);
        }
    }

    /*
     * Extracts the content field of a Sign1Message and converts it into an EdDSAPublicKey
     *
     * @param cert the Sign1Message COSE object
     *
     * @return PublicKey the Ed25519 key that was in the content field of the Sign1Message
     */
    private static PublicKey getEd25519PublicKeyFromCert(Sign1Message cert)
            throws CborException, CryptoException {
        try {
            OneKey key = new OneKey(CBORObject.DecodeFromBytes(cert.GetContent()));
            return byteArrayToEd25519PublicKey(key.get(KeyKeys.OKP_X).ToObject(byte[].class));
        } catch (CoseException e) {
            throw new CborException("Failed to decode certificate",
                                    e, CborException.DESERIALIZATION_ERROR);
        }
    }

    /*
     * Extracts the content field of a Sign1Message and converts it into an
     * X25519PublicKeyParameters object.
     *
     * @param cert the Sign1Message COSE object
     *
     * @return PublicKey the X25519 key that was in the content field of the Sign1Message
     */
    private static X25519PublicKeyParameters getX25519PublicKeyFromCert(Sign1Message cert)
            throws CryptoException {
        byte[] key =
            CBORObject.DecodeFromBytes(
                cert.GetContent()).get(KeyKeys.OKP_X.AsCBOR()).GetByteString();
        return byteArrayToX25519PublicKey(key);
    }

    /*
     * Converts the byte array representation of an Ed25519 public key into an EdDSAPublicKey object
     *
     * @param xCoord the public X coordinate represented as a byte array
     *
     * @return PublicKey the encoded key as a proper EdDSAPublicKey
     */
    public static PublicKey byteArrayToEd25519PublicKey(byte[] xCoord) throws CryptoException {
        try {
            KeyFactory kf = KeyFactory.getInstance("EdDSA", "EdDSA");
            //EdDSANamedCurveSpec paramSpec =
             //   new EdDSANamedCurveSpec(EdDSANamedCurveTable.ED_25519_CURVE_SPEC);
            EdDSAPublicKeySpec pubSpec =
                new EdDSAPublicKeySpec(xCoord, EdDSANamedCurveTable.ED_25519_CURVE_SPEC);
            return kf.generatePublic(pubSpec);
        } catch (InvalidKeySpecException | NoSuchAlgorithmException | NoSuchProviderException e) {
            throw new CryptoException("X25519 provider likely not available",
                                      e, CryptoException.NO_SUCH_ALGORITHM);
        }
    }

    /*
     * Converts the byte array representation of a public key into an X25519PublicKeyParameters
     * object.
     *
     * @param uCoordArr a byte array representing the public portion of an X25519 keypair
     *
     * @return PublicKey the encoded key as an X25519PublicKeyParameters object.
     */
    public static X25519PublicKeyParameters byteArrayToX25519PublicKey(byte[] uCoordArr)
            throws CryptoException {
        return new X25519PublicKeyParameters(uCoordArr, 0 /* offset */);
    }

    public static boolean validateBcc(CBORObject chain)
            throws CborException, CryptoException {
        try {
            Sign1Message certToVerify = (Sign1Message) Message.DecodeFromBytes(
                    chain.get(1).EncodeToBytes(), MessageTag.Sign1);
            OneKey verifyingKey = new OneKey(chain.get(0));
            if (!certToVerify.validate(verifyingKey)) {
                return false;
            }
        } catch (CoseException e) {
            throw new CryptoException("Failed to validate first BCC cert with key",
                                      e, CryptoException.VERIFICATION_FAILURE);
        }
        // TODO: No implementations will have anything more than a device public key and a self
        //       signed root cert in phase 1. Come back and finish functionality for verifying
        //       a chain of signed CWTs and extracting the relevant info
        /*
        Sign1Message signedCwt =
                (Sign1Message) Message.DecodeFromBytes(certToVerifyCbor.EncodeToBytes(),
                                                       MessageTag.Sign1);
        CBORObject cwtMap = CBORObject.DecodeFromBytes(signedCwt.getContent());
        // The 0 index is the the device public key as a COSE_Key object. The 1 index marks the
        // start of the full BccEntry's; COSE_Sign1 objects where the payload is a CBOR Web Token.
        // That CBOR Web Token is a map, in which one of the fields contains the public key that
        // verifies the next BccEntry in the chain.
        CBORObject last = chain.get(1);
        // verify the certificate chain
        for (int i = 0; i < chain.size(); i++) {
            if (!verifyCertWithWebtokenPayload(last, chain.get(i))) {
                return false;
            }
            last = chain.get(i);
        }*/
        return true;
    }

    /**
     * Computes an HKDF.
     *
     * This is based on https://github.com/google/tink/blob/master/java/src/main/java/com/google
     * /crypto/tink/subtle/Hkdf.java
     * which is also Copyright (c) Google and also licensed under the Apache 2 license.
     *
     * @param macAlgorithm the MAC algorithm used for computing the Hkdf. I.e., "HMACSHA1" or
     *                     "HMACSHA256".
     * @param ikm          the input keying material.
     * @param salt         optional salt. A possibly non-secret random value. If no salt is
     *                     provided (i.e. if
     *                     salt has length 0) then an array of 0s of the same size as the hash
     *                     digest is used as salt.
     * @param info         optional context and application specific information.
     * @param size         The length of the generated pseudorandom string in bytes. The maximal
     *                     size is
     *                     255.DigestSize, where DigestSize is the size of the underlying HMAC.
     * @return size pseudorandom bytes.
     */
    private static byte[] computeHkdf(
            String macAlgorithm, final byte[] ikm, final byte[] salt, final byte[] info, int size) {
        Mac mac = null;
        try {
            mac = Mac.getInstance(macAlgorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("No such algorithm: " + macAlgorithm, e);
        }
        if (size > 255 * mac.getMacLength()) {
            throw new RuntimeException("size too large");
        }
        try {
            if (salt == null || salt.length == 0) {
                // According to RFC 5869, Section 2.2 the salt is optional. If no salt is provided
                // then HKDF uses a salt that is an array of zeros of the same length as the hash
                // digest.
                mac.init(new SecretKeySpec(new byte[mac.getMacLength()], macAlgorithm));
            } else {
                mac.init(new SecretKeySpec(salt, macAlgorithm));
            }
            byte[] prk = mac.doFinal(ikm);
            byte[] result = new byte[size];
            int ctr = 1;
            int pos = 0;
            mac.init(new SecretKeySpec(prk, macAlgorithm));
            byte[] digest = new byte[0];
            while (true) {
                mac.update(digest);
                mac.update(info);
                mac.update((byte) ctr);
                digest = mac.doFinal();
                if (pos + digest.length < size) {
                    System.arraycopy(digest, 0, result, pos, digest.length);
                    pos += digest.length;
                    ctr++;
                } else {
                    System.arraycopy(digest, 0, result, pos, size - pos);
                    break;
                }
            }
            return result;
        } catch (InvalidKeyException e) {
            throw new RuntimeException("Error MACing", e);
        }
    }
}
