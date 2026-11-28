package kawaii.viey.browser;

import java.io.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Enumeration;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/**
 * 证书文件的生成与格式转换。
 *
 * 职责边界：
 * - 本类只负责密钥生成、KeyStore 组装、PEM 读取与文件写入；
 * - 证书本身的 ASN.1 构造集中在 {@link CertBuilder}，便于在桌面 JVM 上独立测试。
 *
 * 关于 Provider：早期版本会注册完整的 BouncyCastleProvider，其 JCE 反射注册表会
 * 引入约 1400 个类。此处改用平台自带的 JCA Provider（PKCS12、BKS、RSA、X.509、
 * SHA256withRSA 在 Android 与桌面 JDK 上均由系统实现提供），bouncycastle 只保留
 * PEM 解析与 X.509 构造所需的少量类，交由 R8 按引用裁剪。
 */
public class GenCert {

	private static final String SIGN_ALG = "SHA256withRSA";
	private static final int KEY_SIZE = 2048;
	private static final long VALID_MS = 1000L * 60 * 60 * 24 * 3650L;
	private static final String DEFAULT_ALIAS = "viebrowser";
	private static final String DEFAULT_DN =
		"CN=DMPAP_awzx3214, OU=VieY, O=VieY, L=Guangzhou, ST=Guangdong, C=CN";

	/** 生成 PKCS#12 容器（默认主题与 10 年有效期） */
	public static void genp12(String password, String outputPath) throws Exception {
		char[] pwd = password.toCharArray();
		KeyPair keyPair = generateKeyPair();
		X509Certificate cert = CertBuilder.selfSigned(keyPair, DEFAULT_DN, 3650);
		writeKeyStore(keyPair, cert, DEFAULT_ALIAS, pwd, "PKCS12", outputPath);
	}

	/** PEM 证书与私钥合并为 PKCS#12 */
	public static void pemToP12(String pemCertPath, String pemPrivateKeyPath, String p12Password,
		String outputP12Path, String alias) throws Exception {
		char[] pwd = p12Password.toCharArray();
		X509Certificate cert = readX509CertFromPemFile(pemCertPath);
		PrivateKey privateKey = readPrivateKeyFromPemFile(pemPrivateKeyPath);

		KeyStore p12 = KeyStore.getInstance("PKCS12");
		p12.load(null, null);
		p12.setKeyEntry(alias, privateKey, pwd, new X509Certificate[]{cert});
		writeToFile(p12, pwd, outputP12Path);
	}

	public static void pemToP12(String pemCertPath, String pemPrivateKeyPath, String p12Password,
		String outputP12Path) throws Exception {
		pemToP12(pemCertPath, pemPrivateKeyPath, p12Password, outputP12Path, DEFAULT_ALIAS);
	}

	/** 生成 BKS 容器（默认主题与 10 年有效期） */
	public static void genbks(String password, String outputPath) throws Exception {
		char[] pwd = password.toCharArray();
		KeyPair keyPair = generateKeyPair();
		X509Certificate cert = CertBuilder.selfSigned(keyPair, DEFAULT_DN, 3650);
		writeKeyStore(keyPair, cert, DEFAULT_ALIAS, pwd, "BKS", outputPath);
	}

	/** 生成 PKCS#12 容器（自定义主题与有效期） */
	public static void genp12(String password, String outputPath, String cn, String userId,
		String domain, String org, String country, String email, String dnsCommaList,
		String ipCommaList, int validDays) throws Exception {
		char[] pwd = password.toCharArray();
		KeyPair keyPair = generateKeyPair();
		X509Certificate cert = genCert(keyPair, cn, userId, domain, org, country, email,
			dnsCommaList, ipCommaList, validDays);
		writeKeyStore(keyPair, cert, DEFAULT_ALIAS, pwd, "PKCS12", outputPath);
	}

	/** 生成 BKS 容器（自定义主题与有效期） */
	public static void genbks(String password, String outputPath, String cn, String userId,
		String domain, String org, String country, String email, String dnsCommaList,
		String ipCommaList, int validDays) throws Exception {
		char[] pwd = password.toCharArray();
		KeyPair keyPair = generateKeyPair();
		X509Certificate cert = genCert(keyPair, cn, userId, domain, org, country, email,
			dnsCommaList, ipCommaList, validDays);
		writeKeyStore(keyPair, cert, DEFAULT_ALIAS, pwd, "BKS", outputPath);
	}

	private static X509Certificate genCert(KeyPair keyPair, String cn, String userId,
		String domain, String org, String country, String email, String dnsCommaList,
		String ipCommaList, int validDays) throws Exception {
		StringBuilder dnSb = new StringBuilder();
		if (country != null && !country.trim().isEmpty()) {
			dnSb.append("C=").append(country.trim()).append(",");
		}
		if (org != null && !org.trim().isEmpty()) {
			dnSb.append("O=").append(org.trim()).append(",");
		}
		String realCn = (cn == null || cn.trim().isEmpty()) ? "DMPAP_awzx3214" : cn.trim();
		dnSb.append("CN=").append(realCn);
		return CertBuilder.selfSigned(keyPair, dnSb.toString(), email, dnsCommaList,
			ipCommaList, userId, domain, validDays);
	}

	/**
	 * 把私钥与证书写入 KeyStore 并落盘。
	 * BKS 在 Android 与桌面 JDK 上均由系统 provider 提供，无需额外依赖。
	 */
	private static void writeKeyStore(KeyPair keyPair, X509Certificate cert, String alias,
		char[] pwd, String type, String outputPath) throws Exception {
		KeyStore ks = KeyStore.getInstance(type);
		ks.load(null, null);
		ks.setKeyEntry(alias, keyPair.getPrivate(), pwd, new X509Certificate[]{cert});
		writeToFile(ks, pwd, outputPath);
	}

	private static X509Certificate readX509CertFromPemFile(String pemPath) throws Exception {
		try (FileInputStream fis = new FileInputStream(pemPath)) {
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			return (X509Certificate) cf.generateCertificate(fis);
		}
	}

	private static PrivateKey readPrivateKeyFromPemFile(String keyPath) throws Exception {
		try (FileReader fr = new FileReader(keyPath);
			PEMParser pemParser = new PEMParser(fr)) {
			Object obj = pemParser.readObject();
			JcaPEMKeyConverter converter = new JcaPEMKeyConverter();

			if (obj instanceof PEMKeyPair) {
				PEMKeyPair pemKeyPair = (PEMKeyPair) obj;
				return converter.getPrivateKey(pemKeyPair.getPrivateKeyInfo());
			} else if (obj instanceof PrivateKeyInfo) {
				return converter.getPrivateKey((PrivateKeyInfo) obj);
			}
			throw new IOException(i.getString(R.string.err_pem_key));
		}
	}

	private static KeyPair generateKeyPair() throws Exception {
		KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
		keyGen.initialize(KEY_SIZE);
		return keyGen.generateKeyPair();
	}

	private static void writeToFile(KeyStore ks, char[] pwd, String path) throws Exception {
		File file = new File(path);
		File parent = file.getParentFile();
		if (parent != null && !parent.exists()) {
			parent.mkdirs();
		}
		try (FileOutputStream fos = new FileOutputStream(file)) {
			ks.store(fos, pwd);
		}
	}
}
