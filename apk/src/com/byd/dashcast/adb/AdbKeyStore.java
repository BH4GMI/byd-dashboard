package com.byd.dashcast.adb;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;

/**
 * 应用自管理的 ADB 身份（RSA-2048）。
 *
 * 为什么自生成而不是复用原始 APK 里内嵌的那把私钥：那把钥匙是**原作者的凭据**，
 * 复用它等于借用别人的身份，作者轮换或车机重置即失效；而且把别人私钥打进本程序的
 * APK 也不合适。自生成之后，首次连接时车机会弹一次「允许 USB 调试吗」，用户点允许，
 * 本程序的公钥就进了 /data/misc/adb/adb_keys —— 此后永久有效，不再弹框。
 *
 * 私钥只存在本应用私有目录（/data/data/<pkg>/files/adb_identity），不导出、不联网。
 */
public final class AdbKeyStore {

    private static final String TAG = "DashCastAdbKey";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String FILE_NAME = "adb_identity";

    /** 随包内嵌的身份（PKCS#8 DER）。存在时优先于本地生成。 */
    private static final String ASSET_KEY = "adb_identity.pk8";
    private static final String COMMENT = "dashcast@byd";
    private static final int KEY_BITS = 2048;

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final boolean freshlyCreated;
    /** 身份被重建的原因；null 表示本次沿用了已有身份。 */
    private final String resetReason;

    private AdbKeyStore(PrivateKey priv, RSAPublicKey pub, boolean created) {
        this(priv, pub, created, null);
    }

    private AdbKeyStore(PrivateKey priv, RSAPublicKey pub, boolean created, String resetReason) {
        this.privateKey = priv;
        this.publicKey = pub;
        this.freshlyCreated = created;
        this.resetReason = resetReason;
    }

    /**
     * 本次是不是把已有身份丢了重建；重建则返回原因，沿用则返回 null。
     *
     * 这条信息必须能冒到界面上：身份一换，车机上那把已授权的旧钥匙立刻作废，
     * 用户必然要再授权一次。此前这件事完全静默——界面只说「需要授权」，看不出
     * 是"没授权过"还是"授权过但钥匙被换掉了"。
     */
    public String resetReason() {
        return resetReason;
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    /** 本次调用是否新生成了一对密钥（即车机还没见过它）。 */
    public boolean freshlyCreated() {
        return freshlyCreated;
    }

    /** 公钥指纹（SHA-256 前 16 hex），用于 UI 展示和日志关联。 */
    public String fingerprint() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(publicKeyBlob(publicKey));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            return "unknown";
        }
    }

    // ---- 加载 / 生成 -------------------------------------------------------

    /**
     * 读取已有身份；没有就现生成一对并落盘。
     *
     * 优先级：**随包内嵌的身份** > 本地已存的身份 > 现场生成。
     * 内嵌身份存在的意义有两个：一是让"身份"可被外部指定（便于把同一把钥匙
     * 同时放到 PC 与车机上做对照实验），二是避免重装即换身份。
     *
     * RSA-2048 生成在车机上约需数百毫秒到 1 秒，**不要在主线程调用**。
     */
    public static AdbKeyStore loadOrCreate(Context context)
            throws GeneralSecurityException, IOException {
        AdbKeyStore bundled = tryLoadBundled(context);
        if (bundled != null) {
            return bundled;
        }

        String resetReason = null;
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.isFile()) {
            AdbKeyStore existing = tryLoad(file);
            if (existing != null) {
                return existing;
            }
            // 文件在，却读不出来或公私不配对。这里以前是**静默**删掉重建的：身份一换，
            // 车机上那把已授权的旧钥匙立刻作废，用户必须再授权一次，而日志和界面里
            // 看不出任何异常。现在把原因留下，由调用方冒给用户。
            resetReason = "已存的 ADB 身份文件不可用（" + file.length()
                    + " 字节），已重新生成密钥对；车机上需要重新授权一次";
            Log.w(TAG, resetReason, null);
            file.delete();
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_BITS);
        KeyPair pair = generator.generateKeyPair();

        PrivateKey priv = pair.getPrivate();
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        if (!consistent(priv, pub)) {
            throw new GeneralSecurityException("刚生成的密钥对自检失败");
        }
        write(file, priv, pub);
        return new AdbKeyStore(priv, pub, true, resetReason);
    }

    /**
     * 自检：用**真实的签名路径**签一个 token，再用公钥还原比对。
     *
     * 为什么必须有这一步：私钥与公钥一旦不配对，本程序发出去的公钥会被 adbd 存下，
     * 但每次签名都验不过，现象是"每一条连接都重新弹授权框、却又总能连上"——
     * 表面上功能正常，实际上一直在借系统自动放行，极度难查。这里一次验死。
     */
    static boolean consistent(PrivateKey priv, RSAPublicKey pub) {
        try {
            byte[] token = new byte[20];
            for (int i = 0; i < token.length; i++) {
                token[i] = (byte) (i * 7 + 1);
            }
            byte[] signature = AdbClient.signToken(priv, token);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, pub);
            byte[] recovered = cipher.doFinal(signature);
            return Arrays.equals(recovered, AdbClient.sha1DigestInfo(token));
        } catch (Throwable t) {
            Log.e(TAG, "密钥对自检异常", t);
            return false;
        }
    }

    /**
     * 尝试使用随包内嵌的 PKCS#8 私钥；没有内嵌资产就返回 null（正常情况）。
     * 公钥由私钥推导，所以包里只需放一份私钥 DER。
     */
    private static AdbKeyStore tryLoadBundled(Context context) {
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_KEY);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            byte[] der = buf.toByteArray();

            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey priv = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(priv instanceof RSAPrivateCrtKey)) {
                Log.e(TAG, "内嵌私钥不是 RSA CRT 私钥，忽略");
                return null;
            }
            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
            RSAPublicKey pub = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            if (!consistent(priv, pub)) {
                Log.e(TAG, "内嵌密钥自检失败，忽略");
                return null;
            }
            Log.i(TAG, "使用内嵌的 ADB 身份，模数 " + pub.getModulus().bitLength()
                    + " 位，指数 " + pub.getPublicExponent());
            return new AdbKeyStore(priv, pub, false);
        } catch (java.io.FileNotFoundException e) {
            return null; // 没有内嵌身份，走本地生成
        } catch (Throwable t) {
            Log.e(TAG, "内嵌密钥不可用", t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 读取阶段的问题已在上面归类
                }
            }
        }
    }

    private static AdbKeyStore tryLoad(File file) {        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] raw = new byte[(int) file.length()];
            int off = 0;
            while (off < raw.length) {
                int r = in.read(raw, off, raw.length - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            String[] lines = new String(raw, 0, off, UTF8).split("\n");
            if (lines.length < 2) {
                return null;
            }
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey priv = factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.decode(lines[0].trim(), Base64.DEFAULT)));
            RSAPublicKey pub = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(Base64.decode(lines[1].trim(), Base64.DEFAULT)));
            if (!consistent(priv, pub)) {
                Log.w(TAG, "已存的密钥对不自洽，将重新生成");
                return null;
            }
            return new AdbKeyStore(priv, pub, false);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 读取失败已在上面归类
                }
            }
        }
    }

    /**
     * 把身份写到介质上——**必须耐久，而且必须原子**。
     *
     * 原实现是 `new FileOutputStream(file)` 直接覆写：先截断再写，中间掉电只剩半截；
     * 更要命的是没有 fsync，页缓存里还没落到介质的数据一旦丢失，文件会以「长度正确、
     * 内容全 0」的形态回来（远端出现过等长的全 0 文件）。
     * 身份文件读回是 0 时 {@link #tryLoad} 返回 null，{@link #loadOrCreate} 就换一把
     * 新钥匙——车机上的旧授权立刻失效，于是「每次打开都要重新授权」。
     *
     * 所以这里改成：写临时文件 → fsync 落到介质 → rename 原子替换（POSIX 的 rename
     * 覆盖目标，掉电后要么是旧的完好身份、要么是新的完好身份，不存在半截）→ 回读
     * 逐字节核对。写完就核，是因为只看长度的判据对"长度对、内容全 0"的文件无效。
     */
    private static void write(File file, PrivateKey priv, RSAPublicKey pub) throws IOException {
        String content = Base64.encodeToString(priv.getEncoded(), Base64.NO_WRAP)
                + "\n"
                + Base64.encodeToString(pub.getEncoded(), Base64.NO_WRAP)
                + "\n";
        byte[] data = content.getBytes(UTF8);

        File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(data);
            out.flush();
            out.getFD().sync();
        } finally {
            if (out != null) {
                out.close();
            }
        }

        if (!tmp.renameTo(file)) {
            // 少数实现上 rename 不覆盖已存在的目标，退一步显式删除再改名。
            if (!(file.delete() && tmp.renameTo(file))) {
                tmp.delete();
                throw new IOException("无法把 ADB 身份落定到 " + file);
            }
        }

        byte[] back = readAll(file);
        if (!Arrays.equals(back, data)) {
            throw new IOException("ADB 身份回读不一致：写入 " + data.length
                    + " 字节，读回 " + back.length + " 字节");
        }
    }

    /** 整份读回，用于写入后的逐字节核对。 */
    private static byte[] readAll(File file) throws IOException {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream buf = new ByteArrayOutputStream((int) file.length());
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    // ---- ADB 公钥格式 ------------------------------------------------------

    /**
     * ADB 的 ANDROID_PUBKEY_STRUCT（全部小端）：
     *
     *   uint32 nwords                模数长度，单位 32 位字
     *   uint32 n0inv                 = -1 / n[0] mod 2^32
     *   uint8  n[nwords*4]           模数，小端
     *   uint32 rr[nwords]            = (2^(32*nwords))^2 mod n，小端
     *   uint32 exponent              公钥指数，小端
     *
     * 2048 位密钥的结构体长度为 4+4+256+256+4 = **524** 字节，也就是 adbd 的
     * ANDROID_PUBKEY_ENCODED_SIZE。**指数必须写进去**：adbd 在
     * daemon/auth.cpp 里对 adb_keys 的每一行做
     *
     *   if (b64_pton(pubkey, keybuf, sizeof(keybuf)) != ANDROID_PUBKEY_ENCODED_SIZE)
     *       LOG(ERROR) << "Invalid base64 key " << pubkey;
     *
     * 只要解码长度不是 524，整条密钥就被当成垃圾丢掉——写进 adb_keys 也永不生效，
     * 现象就是"每次连接都重新弹授权框"。少写这 4 字节正是这个现象的根因。
     */
    static byte[] publicKeyBlob(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        int nwords = (n.bitLength() + 31) / 32;
        int bytes = nwords * 4;

        byte[] nBytes = AdbClient.toLittleEndian(n, bytes);
        long n0 = AdbClient.readUInt32LE(nBytes, 0);

        BigInteger twoPow32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = BigInteger.valueOf(n0)
                .modInverse(twoPow32)
                .negate()
                .mod(twoPow32);

        BigInteger r = BigInteger.ONE.shiftLeft(nwords * 32);
        BigInteger rr = r.multiply(r).mod(n);
        byte[] rrBytes = AdbClient.toLittleEndian(rr, bytes);

        int rrOff = 8 + bytes;
        int eOff = rrOff + bytes;
        byte[] out = new byte[eOff + 4];
        putUInt32LE(out, 0, nwords);
        putUInt32LE(out, 4, n0inv.longValue());
        System.arraycopy(nBytes, 0, out, 8, bytes);
        System.arraycopy(rrBytes, 0, out, rrOff, bytes);
        putUInt32LE(out, eOff, key.getPublicExponent().longValue());
        return out;
    }

    /** 人可读形式：`<base64> dashcast@byd`，与 `adb` 的 .pub 文件一致。 */
    public static String publicKeyText(RSAPublicKey key) {
        return Base64.encodeToString(publicKeyBlob(key), Base64.NO_WRAP) + " " + COMMENT;
    }

    /** AUTH(arg0=3) 要发的载荷：公钥文本 + NUL。 */
    static byte[] publicKeyPayload(RSAPublicKey key) {
        byte[] text = publicKeyText(key).getBytes(UTF8);
        return Arrays.copyOf(text, text.length + 1);
    }

    private static void putUInt32LE(byte[] b, int off, long v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
