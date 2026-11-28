package kawaii.viey.browser;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.OtherName;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * X.509 自签名证书构造。
 *
 * 该类不依赖 Android API，可在桌面 JVM 上直接测试；证书生成所需的 bouncycastle
 * 类全部为直接调用（无反射入口），因此可以安全地交给 R8 按引用裁剪。
 *
 * 签名与密钥生成走平台 JCA Provider（Android 与桌面 JDK 均原生支持 SHA256withRSA），
 * 不再注册 BouncyCastleProvider —— 其反射注册表会引入约 1400 个类，是安装包体积的
 * 主要来源。
 */
public final class CertBuilder {

    public static final String SIGN_ALG = "SHA256withRSA";

    /** userId 对应的 otherName OID，沿用项目既有约定 */
    public static final String OID_USER_ID = "1.2.3.4.1";
    /** domain 对应的 otherName OID，沿用项目既有约定 */
    public static final String OID_DOMAIN = "1.2.3.4.2";

    private CertBuilder() {
    }

    /**
     * 生成自签名证书。
     *
     * @param keyPair   密钥对，公钥写入证书主体
     * @param dn        可分辨名称，如 "CN=xxx,O=xxx,C=CN"
     * @param email     邮件地址，空则忽略
     * @param dnsList   域名列表，逗号分隔，空则忽略
     * @param ipList    地址列表，逗号分隔（IPv4），空则忽略
     * @param userId    用户标识，写入 otherName(OID 1.2.3.4.1)，空则忽略
     * @param domain    域名标识，写入 otherName(OID 1.2.3.4.2)，空则忽略
     * @param validDays 有效期天数
     */
    public static X509Certificate selfSigned(KeyPair keyPair, String dn, String email,
                                             String dnsList, String ipList, String userId,
                                             String domain, int validDays) throws Exception {
        long now = System.currentTimeMillis();
        Date startDate = new Date(now);
        long validMs = 1000L * 60 * 60 * 24 * (long) validDays;
        Date endDate = new Date(now + validMs);

        X500Name name = new X500Name(dn);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.ONE, startDate, endDate, name, keyPair.getPublic());

        List<GeneralName> names = new ArrayList<>();

        if (notBlank(email)) {
            names.add(new GeneralName(GeneralName.rfc822Name, email.trim()));
        }
        if (notBlank(dnsList)) {
            for (String d : dnsList.split(",")) {
                if (notBlank(d)) names.add(new GeneralName(GeneralName.dNSName, d.trim()));
            }
        }
        if (notBlank(ipList)) {
            for (String s : ipList.split(",")) {
                byte[] ip = ipv4ToBytes(s);
                if (ip != null) {
                    names.add(new GeneralName(GeneralName.iPAddress, new DEROctetString(ip)));
                }
            }
        }
        if (notBlank(userId)) {
            names.add(otherName(OID_USER_ID, userId.trim()));
        }
        if (notBlank(domain)) {
            names.add(otherName(OID_DOMAIN, domain.trim()));
        }

        if (!names.isEmpty()) {
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(names.toArray(new GeneralName[0])));
        }

        ContentSigner signer = new JcaContentSignerBuilder(SIGN_ALG).build(keyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    /** 仅指定可分辨名称与有效期的最小形式 */
    public static X509Certificate selfSigned(KeyPair keyPair, String dn, int validDays)
            throws Exception {
        return selfSigned(keyPair, dn, null, null, null, null, null, validDays);
    }

    private static GeneralName otherName(String oid, String value) {
        return new GeneralName(GeneralName.otherName,
                new OtherName(new ASN1ObjectIdentifier(oid), new DERUTF8String(value)));
    }

    /** 点分十进制 IPv4 转 4 字节，非法输入返回 null */
    static byte[] ipv4ToBytes(String ipStr) {
        try {
            String[] parts = ipStr.trim().split("\\.");
            if (parts.length != 4) return null;
            byte[] buf = new byte[4];
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) return null;
                buf[i] = (byte) (v & 0xff);
            }
            return buf;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
