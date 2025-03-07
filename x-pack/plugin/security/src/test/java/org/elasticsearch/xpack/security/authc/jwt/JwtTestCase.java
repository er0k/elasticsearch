/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.security.authc.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.JWKGenerator;
import com.nimbusds.jose.jwk.gen.OctetSequenceKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.openid.connect.sdk.Nonce;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.core.PathUtils;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.security.authc.RealmConfig;
import org.elasticsearch.xpack.core.security.authc.RealmSettings;
import org.elasticsearch.xpack.core.security.authc.jwt.JwtRealmSettings;
import org.elasticsearch.xpack.core.security.authc.support.DelegatedAuthorizationSettings;
import org.elasticsearch.xpack.core.security.authc.support.UserRoleMapper;
import org.elasticsearch.xpack.core.security.user.User;
import org.elasticsearch.xpack.core.ssl.SSLConfigurationSettings;
import org.junit.Before;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.elasticsearch.test.ActionListenerUtils.anyActionListener;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

public abstract class JwtTestCase extends ESTestCase {

    private static final Logger LOGGER = LogManager.getLogger(JwtTestCase.class);

    protected String pathHome;
    protected Settings globalSettings;
    protected Environment env;
    protected ThreadContext threadContext;

    @Before
    public void beforeEachTest() {
        this.pathHome = createTempDir().toString();
        this.globalSettings = Settings.builder().put(Environment.PATH_HOME_SETTING.getKey(), this.pathHome).build();
        this.env = TestEnvironment.newEnvironment(this.globalSettings); // "path.home" sub-dirs: config,plugins,data,logs,bin,lib,modules
        this.threadContext = new ThreadContext(this.globalSettings);
    }

    protected Settings.Builder generateRandomRealmSettings(final String name) throws IOException {
        final boolean includeRsa = randomBoolean();
        final boolean includeEc = randomBoolean();
        final boolean includePublicKey = includeRsa || includeEc;
        final boolean includeHmac = randomBoolean() || (includePublicKey == false); // one of HMAC/RSA/EC must be true
        final boolean populateUserMetadata = randomBoolean();
        final Path jwtSetPathObj = PathUtils.get(this.pathHome);
        final String jwkSetPath = randomBoolean()
            ? "https://op.example.com/jwkset.json"
            : Files.createTempFile(jwtSetPathObj, "jwkset.", ".json").toString();

        if (jwkSetPath.equals("https://op.example.com/jwkset.json") == false) {
            Files.writeString(PathUtils.get(jwkSetPath), "Non-empty JWK Set Path contents");
        }
        final String clientAuthenticationType = randomFrom(JwtRealmSettings.CLIENT_AUTHENTICATION_TYPES);

        final List<String> allowedSignatureAlgorithmsList = new ArrayList<>();
        if (includeRsa) {
            allowedSignatureAlgorithmsList.add(randomFrom(JwtRealmSettings.SUPPORTED_SIGNATURE_ALGORITHMS_RSA));
        }
        if (includeEc) {
            allowedSignatureAlgorithmsList.add(randomFrom(JwtRealmSettings.SUPPORTED_SIGNATURE_ALGORITHMS_EC));
        }
        if (includeHmac) {
            allowedSignatureAlgorithmsList.add(randomFrom(JwtRealmSettings.SUPPORTED_SIGNATURE_ALGORITHMS_HMAC));
        }
        final String allowedSignatureAlgorithms = allowedSignatureAlgorithmsList.toString(); // Ex: "[HS256,RS384,ES512]"

        final Settings.Builder settingsBuilder = Settings.builder()
            // Issuer settings
            .put(
                RealmSettings.getFullSettingKey(name, JwtRealmSettings.ALLOWED_ISSUER),
                randomFrom(randomFrom("https://www.example.com/", "") + "iss1" + randomIntBetween(0, 99))
            )
            .put(
                RealmSettings.getFullSettingKey(name, JwtRealmSettings.ALLOWED_SIGNATURE_ALGORITHMS),
                allowedSignatureAlgorithms.substring(1, allowedSignatureAlgorithms.length() - 1)
            )
            .put(
                RealmSettings.getFullSettingKey(name, JwtRealmSettings.ALLOWED_CLOCK_SKEW),
                randomBoolean() ? "-1" : randomBoolean() ? "0" : randomIntBetween(1, 5) + randomFrom("s", "m", "h")
            )
            .put(RealmSettings.getFullSettingKey(name, JwtRealmSettings.PKC_JWKSET_PATH), includePublicKey ? jwkSetPath : "")
            // Audience settings
            .put(
                RealmSettings.getFullSettingKey(name, JwtRealmSettings.ALLOWED_AUDIENCES),
                randomFrom("rp_client1", "aud1", "aud2", "aud3")
            )
            // End-user settings
            .put(
                RealmSettings.getFullSettingKey(name, JwtRealmSettings.CLAIMS_PRINCIPAL.getClaim()),
                randomFrom("sub", "uid", "name", "dn", "email", "custom")
            )
            .put(
                RealmSettings.getFullSettingKey(name, JwtRealmSettings.CLAIMS_PRINCIPAL.getPattern()),
                randomBoolean() ? null : randomFrom("^(.*)$", "^([^@]+)@example\\.com$")
            )
            .put(RealmSettings.getFullSettingKey(name, JwtRealmSettings.CLAIMS_GROUPS.getClaim()), randomFrom("group", "roles", "other"))
            .put(
                RealmSettings.getFullSettingKey(name, JwtRealmSettings.CLAIMS_GROUPS.getPattern()),
                randomBoolean() ? null : randomFrom("^(.*)$", "^Group-(.*)$")
            )
            .put(RealmSettings.getFullSettingKey(name, JwtRealmSettings.POPULATE_USER_METADATA), populateUserMetadata)
            // Client settings for incoming connections
            .put(RealmSettings.getFullSettingKey(name, JwtRealmSettings.CLIENT_AUTHENTICATION_TYPE), clientAuthenticationType)
            // Delegated authentication settings
            .put(
                RealmSettings.getFullSettingKey(name, DelegatedAuthorizationSettings.AUTHZ_REALMS.apply(JwtRealmSettings.TYPE)),
                randomBoolean() ? "" : "authz1, authz2"
            )
            // HTTP settings for outgoing connections
            .put(
                RealmSettings.getFullSettingKey(name, JwtRealmSettings.HTTP_CONNECT_TIMEOUT),
                randomBoolean() ? "-1" : randomBoolean() ? "0" : randomIntBetween(1, 5) + randomFrom("s", "m", "h")
            )
            .put(
                RealmSettings.getFullSettingKey(name, JwtRealmSettings.HTTP_CONNECTION_READ_TIMEOUT),
                randomBoolean() ? "-1" : randomBoolean() ? "0" : randomIntBetween(5, 10) + randomFrom("s", "m", "h")
            )
            .put(
                RealmSettings.getFullSettingKey(name, JwtRealmSettings.HTTP_SOCKET_TIMEOUT),
                randomBoolean() ? "-1" : randomBoolean() ? "0" : randomIntBetween(5, 10) + randomFrom("s", "m", "h")
            )
            .put(RealmSettings.getFullSettingKey(name, JwtRealmSettings.HTTP_MAX_CONNECTIONS), randomIntBetween(5, 20))
            .put(RealmSettings.getFullSettingKey(name, JwtRealmSettings.HTTP_MAX_ENDPOINT_CONNECTIONS), randomIntBetween(5, 20))
            // TLS settings for outgoing connections
            .put(RealmSettings.getFullSettingKey(name, SSLConfigurationSettings.TRUSTSTORE_TYPE.realm(JwtRealmSettings.TYPE)), "PKCS12")
            .put(RealmSettings.getFullSettingKey(name, SSLConfigurationSettings.TRUSTSTORE_PATH.realm(JwtRealmSettings.TYPE)), "ts2.p12")
            .put(RealmSettings.getFullSettingKey(name, SSLConfigurationSettings.TRUSTSTORE_ALGORITHM.realm(JwtRealmSettings.TYPE)), "PKIX")
            .put(RealmSettings.getFullSettingKey(name, SSLConfigurationSettings.CERT_AUTH_PATH.realm(JwtRealmSettings.TYPE)), "ca2.pem");

        final MockSecureSettings secureSettings = new MockSecureSettings();
        if (includeHmac) {
            if (randomBoolean()) {
                secureSettings.setString(
                    RealmSettings.getFullSettingKey(name, JwtRealmSettings.HMAC_JWKSET),
                    randomAlphaOfLengthBetween(10, 20)
                );
            } else {
                secureSettings.setString(
                    RealmSettings.getFullSettingKey(name, JwtRealmSettings.HMAC_KEY),
                    randomAlphaOfLengthBetween(10, 20)
                );
            }
        }
        if (JwtRealmSettings.CLIENT_AUTHENTICATION_TYPE_SHARED_SECRET.equals(clientAuthenticationType)) {
            secureSettings.setString(
                RealmSettings.getFullSettingKey(name, JwtRealmSettings.CLIENT_AUTHENTICATION_SHARED_SECRET),
                randomAlphaOfLengthBetween(8, 12)
            );
        }
        secureSettings.setString(
            RealmSettings.getFullSettingKey(name, SSLConfigurationSettings.TRUSTSTORE_PASSWORD.realm(JwtRealmSettings.TYPE)),
            randomAlphaOfLengthBetween(10, 10)
        );

        settingsBuilder.setSecureSettings(secureSettings);
        return settingsBuilder;
    }

    protected RealmConfig buildRealmConfig(
        final String realmType,
        final String realmName,
        final Settings realmSettings,
        final int realmOrder
    ) {
        final RealmConfig.RealmIdentifier realmIdentifier = new RealmConfig.RealmIdentifier(realmType, realmName);
        final Settings settings = Settings.builder()
            .put(this.globalSettings)
            // .put("path.home", this.pathHome)
            .put(realmSettings)
            .put(RealmSettings.getFullSettingKey(realmIdentifier, RealmSettings.ORDER_SETTING), realmOrder)
            .build();
        return new RealmConfig(realmIdentifier, settings, this.env, this.threadContext);
    }

    protected Answer<Class<Void>> getAnswer(AtomicReference<UserRoleMapper.UserData> userData) {
        return invocation -> {
            userData.set((UserRoleMapper.UserData) invocation.getArguments()[0]);
            @SuppressWarnings("unchecked")
            ActionListener<Set<String>> listener = (ActionListener<Set<String>>) invocation.getArguments()[1];
            listener.onResponse(new HashSet<>(Arrays.asList("kibana_user", "role1")));
            return null;
        };
    }

    protected UserRoleMapper buildRoleMapper(final Map<String, User> registeredUsers) {
        final UserRoleMapper roleMapper = mock(UserRoleMapper.class);
        Mockito.doAnswer(invocation -> {
            final UserRoleMapper.UserData userData = (UserRoleMapper.UserData) invocation.getArguments()[0];
            @SuppressWarnings("unchecked")
            final ActionListener<Set<String>> listener = (ActionListener<Set<String>>) invocation.getArguments()[1];
            final User registeredUser = registeredUsers.get(userData.getUsername());
            if (registeredUser == null) {
                listener.onFailure(new IllegalArgumentException("Expected principal '" + userData.getUsername() + "' not found."));
            } else {
                listener.onResponse(Set.of(registeredUser.roles()));
            }
            return null;
        }).when(roleMapper).resolveRoles(any(UserRoleMapper.UserData.class), anyActionListener());
        return roleMapper;
    }

    public static List<JwtIssuer.AlgJwkPair> randomJwks(final List<String> signatureAlgorithms) throws JOSEException {
        final List<JwtIssuer.AlgJwkPair> algAndJwks = new ArrayList<>();
        for (final String signatureAlgorithm : signatureAlgorithms) {
            algAndJwks.add(new JwtIssuer.AlgJwkPair(signatureAlgorithm, JwtTestCase.randomJwk(signatureAlgorithm)));
        }
        return algAndJwks;
    }

    public static JWK randomJwk(final String signatureAlgorithm) throws JOSEException {
        final JWSAlgorithm jwsAlgorithm = JWSAlgorithm.parse(signatureAlgorithm);
        if (JwtRealmSettings.SUPPORTED_SIGNATURE_ALGORITHMS_HMAC.contains(signatureAlgorithm)) {
            return JwtTestCase.randomJwkHmac(jwsAlgorithm);
        } else if (JwtRealmSettings.SUPPORTED_SIGNATURE_ALGORITHMS_RSA.contains(signatureAlgorithm)) {
            return JwtTestCase.randomJwkRsa(jwsAlgorithm);
        } else if (JwtRealmSettings.SUPPORTED_SIGNATURE_ALGORITHMS_EC.contains(signatureAlgorithm)) {
            return JwtTestCase.randomJwkEc(jwsAlgorithm);
        }
        throw new JOSEException(
            "Unsupported signature algorithm ["
                + signatureAlgorithm
                + "]. Supported signature algorithms are "
                + JwtRealmSettings.SUPPORTED_SIGNATURE_ALGORITHMS
                + "."
        );
    }

    // Generate using random bytes
    // - random byte => 2^8 => search space 8-bit per byte
    public static OctetSequenceKey randomJwkHmac(final JWSAlgorithm jwsAlgorithm) throws JOSEException {
        final int minHmacLengthBytes = MACSigner.getMinRequiredSecretLength(jwsAlgorithm) / 8;
        final int hmacLengthBits = scaledRandomIntBetween(minHmacLengthBytes, minHmacLengthBytes * 2) * 8; // Double it: Nice to have
        final OctetSequenceKeyGenerator jwkGenerator = new OctetSequenceKeyGenerator(hmacLengthBits);
        JwtTestCase.randomSettingsForJwkGenerator(jwkGenerator, jwsAlgorithm); // options: kid, alg, use, ops
        return jwkGenerator.generate();
    }

    public static RSAKey randomJwkRsa(final JWSAlgorithm jwsAlgorithm) throws JOSEException {
        final int rsaLengthBits = rarely() ? 3072 : 2048;
        final RSAKeyGenerator jwkGenerator = new RSAKeyGenerator(rsaLengthBits, false);
        JwtTestCase.randomSettingsForJwkGenerator(jwkGenerator, jwsAlgorithm); // options: kid, alg, use, ops
        return jwkGenerator.generate();
    }

    public static ECKey randomJwkEc(final JWSAlgorithm jwsAlgorithm) throws JOSEException {
        final Curve ecCurve = randomFrom(Curve.forJWSAlgorithm(jwsAlgorithm));
        final ECKeyGenerator jwkGenerator = new ECKeyGenerator(ecCurve);
        JwtTestCase.randomSettingsForJwkGenerator(jwkGenerator, jwsAlgorithm); // options: kid, alg, use, ops
        return jwkGenerator.generate();
    }

    public static OctetSequenceKey randomJwkHmacOidc(final JWSAlgorithm jwsAlgorithm) throws JOSEException {
        return JwtTestCase.conditionJwkHmacForOidc(JwtTestCase.randomJwkHmac(jwsAlgorithm));
    }

    /**
     *  Input HMAC key is assumed random bytes. Generating random bytes is useful to guarantee min search space (aka strength, entropy).
     *
     *  OIDC HMAC key must be UTF8 bytes (aka password). Encoding random bytes as UTF8 doesn't work, and UTF8 search space is smaller.
     *
     *  To satisfy min search space and OIDC UTF8 encoding, Base64(randomBytes) is used as the bytes of a new HMAC OIDC key.
     *
     *  Search space comparisons of random bytes, base 64, and UTF-8.
     *  - random byte => 2^8 search space per 1 byte => 8 bits per byte
     *  - Base 64 byte => 2^6 search space per 1 byte => 6 bits per byte
     *  - UTF8 1 byte => 2^7 search space per 1 byte => 7 bits per byte
     *  - UTF8 2 byte => 2^11 search space per 2 byte => 5.5 bits per byte
     *  - UTF8 3 byte => 2^16 search space per 3 byte => 5.333 bits per byte
     *  - UTF8 4 byte => 2^21 search space per 4 byte => 5.25 bits per byte (theoretical, UNICODE currently only allocates 1.1M of 2M)
     *
     * @param hmacKey HMAC key with random bytes.
     * @return HMAC key with UTF-8 bytes, making the key bytes compatible with OIDC UTF-8 string encoding.
     */
    public static OctetSequenceKey conditionJwkHmacForOidc(final OctetSequenceKey hmacKey) {
        final String passwordKey;
        if (randomBoolean()) {
            final Base64URL hmacKeyBytesBase64 = hmacKey.getKeyValue(); // Random bytes => 8 bits/byte search space
            passwordKey = hmacKeyBytesBase64.toString(); // Use Base64(randomBytes) as UTF8 bytes for a new password with same search space
        } else {
            final int numLetters = hmacKey.toByteArray().length * 8; // Random [A-Za-z] => 5.7 bits/byte
            passwordKey = randomAlphaOfLength(numLetters); // Use length * ceil(2.3) to avoid reducing search space below 8 bits/byte
        }
        final OctetSequenceKey.Builder hmacKeyBuilder = new OctetSequenceKey.Builder(passwordKey.getBytes(StandardCharsets.UTF_8));
        hmacKeyBuilder.keyID(hmacKey.getKeyID()); // Copy null attribute is OK (no-op)
        hmacKeyBuilder.algorithm(hmacKey.getAlgorithm());
        hmacKeyBuilder.keyUse(hmacKey.getKeyUse());
        hmacKeyBuilder.keyOperations(hmacKey.getKeyOperations());
        hmacKeyBuilder.keyStore(hmacKey.getKeyStore());
        return hmacKeyBuilder.build();
    }

    public static OctetSequenceKey jwkHmacRemoveAttributes(final OctetSequenceKey hmacKey) {
        final String keyBytesAsUtf8 = hmacKey.getKeyValue().decodeToString();
        return new OctetSequenceKey.Builder(keyBytesAsUtf8.getBytes(StandardCharsets.UTF_8)).build();
    }

    public static JWKGenerator<? extends JWK> randomSettingsForJwkGenerator(
        final JWKGenerator<? extends JWK> jwkGenerator,
        final JWSAlgorithm jwsAlgorithm
    ) {
        if (randomBoolean()) {
            jwkGenerator.keyID(UUID.randomUUID().toString());
        }
        if (randomBoolean()) {
            jwkGenerator.algorithm(jwsAlgorithm);
        }
        if (randomBoolean()) {
            jwkGenerator.keyUse(KeyUse.SIGNATURE);
        }
        if (randomBoolean()) {
            jwkGenerator.keyOperations(Set.of(KeyOperation.SIGN, KeyOperation.VERIFY));
        }
        return jwkGenerator;
    }

    public static SignedJWT buildUnsignedJwt(
        final String type,
        final String signatureAlgorithm,
        final String jwtId,
        final String issuer,
        final List<String> audiences,
        final String subject,
        final String principalClaimName,
        final String principalClaimValue,
        final String groupsClaimName,
        final List<String> groupsClaimValue,
        final Date authTime,
        final Date iat,
        final Date nbf,
        final Date exp,
        final String nonce,
        final Map<String, Object> otherClaims
    ) {
        final JWSHeader.Builder jwsHeaderBuilder = new JWSHeader.Builder(JWSAlgorithm.parse(signatureAlgorithm));
        if (type != null) {
            jwsHeaderBuilder.type(new JOSEObjectType(type));
        }
        final JWSHeader jwtHeader = jwsHeaderBuilder.build();
        final JWTClaimsSet.Builder jwtClaimsSetBuilder = new JWTClaimsSet.Builder();
        if (jwtId != null) {
            jwtClaimsSetBuilder.jwtID(jwtId);
        }
        if (issuer != null) {
            jwtClaimsSetBuilder.issuer(issuer);
        }
        if (audiences != null) {
            if (audiences.stream().anyMatch(a -> Strings.hasText(a) == false)) {
                throw new IllegalArgumentException("Null or blank audience not allowed.");
            }
            jwtClaimsSetBuilder.audience(audiences);
        }
        if (subject != null) {
            jwtClaimsSetBuilder.subject(subject);
        }
        // principal and groups claims
        if ((Strings.hasText(principalClaimName)) && (principalClaimValue != null)) {
            jwtClaimsSetBuilder.claim(principalClaimName, principalClaimValue);
        }
        if ((Strings.hasText(groupsClaimName)) && (groupsClaimValue != null)) {
            jwtClaimsSetBuilder.claim(groupsClaimName, groupsClaimValue.toString());
        }
        if (authTime != null) {
            jwtClaimsSetBuilder.claim("auth_time", authTime);
        }
        if (nbf != null) {
            jwtClaimsSetBuilder.notBeforeTime(nbf);
        }
        if (iat != null) {
            jwtClaimsSetBuilder.issueTime(iat);
        }
        if (exp != null) {
            jwtClaimsSetBuilder.expirationTime(exp);
        }
        if (nonce != null) {
            jwtClaimsSetBuilder.claim("nonce", nonce);
        }
        // Custom extra claims. Principal claim name could be "sub" or something else
        if (otherClaims != null) {
            for (final Map.Entry<String, Object> entry : otherClaims.entrySet()) {
                if (Strings.hasText(entry.getKey()) == false) {
                    throw new IllegalArgumentException("Null or blank other claim key allowed.");
                } else if (entry.getValue() == null) {
                    throw new IllegalArgumentException("Null other claim value allowed.");
                }
                jwtClaimsSetBuilder.claim(entry.getKey(), entry.getValue());
            }
        }
        final JWTClaimsSet jwtClaimsSet = jwtClaimsSetBuilder.build();
        LOGGER.info(
            "CLAIMS: alg=["
                + jwtHeader.getAlgorithm().getName()
                + "], jwtId=["
                + jwtClaimsSet.getJWTID()
                + "], iss=["
                + jwtClaimsSet.getIssuer()
                + "], aud="
                + jwtClaimsSet.getAudience()
                + ", sub=["
                + jwtClaimsSet.getSubject()
                + "], principalClaim=["
                + principalClaimName
                + "="
                + jwtClaimsSet.getClaim(principalClaimName)
                + "], groupsClaim=["
                + groupsClaimName
                + "="
                + jwtClaimsSet.getClaim(groupsClaimName)
                + "], nbf=["
                + jwtClaimsSet.getNotBeforeTime()
                + "], auth_time=["
                + jwtClaimsSet.getClaim("auth_time")
                + "], iat=["
                + jwtClaimsSet.getIssueTime()
                + "], exp=["
                + jwtClaimsSet.getExpirationTime()
                + "], nonce=["
                + jwtClaimsSet.getClaim("nonce")
                + "], other=["
                + otherClaims
                + "]"
        );
        return JwtValidateUtil.buildUnsignedJwt(jwtHeader, jwtClaimsSet);
    }

    public static SecureString randomJwt(final JWK jwk, final String signatureAlgorithm) throws Exception {
        final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final SignedJWT unsignedJwt = JwtTestCase.buildUnsignedJwt(
            randomBoolean() ? null : JOSEObjectType.JWT.toString(),
            signatureAlgorithm, // alg
            randomAlphaOfLengthBetween(10, 20), // jwtID
            randomFrom("https://www.example.com/", "") + "iss1" + randomIntBetween(0, 99),
            randomFrom(List.of("rp_client1"), List.of("aud1", "aud2", "aud3")),
            randomBoolean() ? "principal1" : "subject1",
            randomFrom("sub", "uid", "custom"),
            "principal1",
            randomBoolean() ? null : randomFrom("groups", "roles", "other"),
            randomFrom(List.of(""), List.of("grp1"), List.of("rol1", "rol2", "rol3"), List.of("per1")),
            Date.from(now.minusSeconds(randomLongBetween(10, 20))), // auth_time
            Date.from(now), // iat
            Date.from(now.minusSeconds(randomLongBetween(5, 10))), // nbf
            Date.from(now.plusSeconds(randomLongBetween(3600, 7200))), // exp
            randomBoolean() ? null : new Nonce(32).toString(),
            randomBoolean() ? null : Map.of("other1", randomAlphaOfLength(10), "other2", randomAlphaOfLength(10))
        );
        return JwtValidateUtil.signJwt(jwk, unsignedJwt);
    }

    public static Map<String, User> generateTestUsersWithRoles(final int numUsers, final int numRolesPerUser) {
        final Map<String, User> testUsers = new LinkedHashMap<>();
        for (int i = 0; i < numUsers; i++) {
            final String principal = "principal" + i;
            testUsers.put(
                principal,
                new User(
                    principal,
                    IntStream.range(0, numRolesPerUser).mapToObj(j -> "role" + j).toArray(String[]::new),
                    null,
                    null,
                    randomBoolean() ? Collections.singletonMap("metadata", i) : null,
                    true
                )
            );
        }
        return testUsers;
    }

    public static <T> List<T> randomOfUnique(final Collection<T> collection) {
        return randomOfMinMaxUnique(0, collection.size(), collection);
    }

    public static <T> List<T> randomOfNonUnique(final Collection<T> collection) {
        return randomOfMinMaxNonUnique(0, collection.size(), collection);
    }

    public static <T> List<T> randomOfMinUnique(final int min, final Collection<T> collection) {
        return randomOfMinMaxUnique(min, collection.size(), collection);
    }

    public static <T> List<T> randomOfMinNonUnique(final int min, final Collection<T> collection) {
        return randomOfMinMaxNonUnique(min, collection.size(), collection);
    }

    public static <T> List<T> randomOfMaxUnique(final int max, final Collection<T> collection) {
        return randomOfMinMaxUnique(0, max, collection);
    }

    public static <T> List<T> randomOfMaxNonUnique(final int max, final Collection<T> collection) {
        return randomOfMinMaxNonUnique(0, max, collection);
    }

    public static <T> List<T> randomOfMinMaxUnique(final int min, final int max, final Collection<T> collection) {
        assert min >= 0 : "min has to be at least zero";
        assert max >= min : "max has to be at least min";
        assert collection.size() >= max : "max has to be at least collection size";
        final int minToMaxInclusive = randomIntBetween(min, max); // min..max inclusive
        return randomSubsetOf(minToMaxInclusive, collection);
    }

    public static <T> List<T> randomOfMinMaxNonUnique(final int min, final int max, final Collection<T> collection) {
        assert min >= 0 : "min has to be at least zero";
        assert max >= min : "max has to be at least min";
        assert (min == 0) || (collection.isEmpty() == false) : "if min!=0, collection must be non-empty";
        final int minToMaxInclusive = randomIntBetween(min, max); // min..max inclusive
        return IntStream.rangeClosed(1, minToMaxInclusive).mapToObj(i -> randomFrom(collection)).toList(); // 1..N inclusive
    }

    public String saveJwkSetToTempFile(final JWKSet jwksetPkc, final boolean publicKeysOnly) throws IOException {
        final String serializedJwkSet = JwtUtil.serializeJwkSet(jwksetPkc, publicKeysOnly);
        if (serializedJwkSet == null) {
            return null;
        }
        final Path path = Files.createTempFile(PathUtils.get(this.pathHome), "jwkset.", ".json");
        Files.writeString(path, serializedJwkSet);
        return path.toString();
    }

    public ThreadContext createThreadContext(final CharSequence jwt, final CharSequence sharedSecret) {
        final ThreadContext requestThreadContext = new ThreadContext(this.globalSettings);
        if (jwt != null) {
            requestThreadContext.putHeader(
                JwtRealm.HEADER_END_USER_AUTHENTICATION,
                JwtRealm.HEADER_END_USER_AUTHENTICATION_SCHEME + " " + jwt
            );
        }
        if (sharedSecret != null) {
            requestThreadContext.putHeader(
                JwtRealm.HEADER_CLIENT_AUTHENTICATION,
                JwtRealmSettings.CLIENT_AUTHENTICATION_TYPE_SHARED_SECRET + " " + sharedSecret
            );
        }
        return requestThreadContext;
    }
}
