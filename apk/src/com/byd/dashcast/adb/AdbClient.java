package com.byd.dashcast.adb;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;

import javax.crypto.Cipher;

/**
 * ADB 线协议客户端。存在的唯一理由：**让 App 自己拿到 uid 2000**。
 *
 * 为什么需要它：这台车机上
 *   - `setLaunchDisplayId(2)` 从普通应用发起会被 AMS 拒绝
 *     （报错为 SecurityException: Permission Denial ... with launchDisplayId=2），
 *   - 输入注入需要 INJECT_EVENTS，是 signature|privileged。
 * 两者都只有 uid 2000(shell) 能做。而 adbd 就监听在车机本地 127.0.0.1:5555，
 * 通过 AUTH 之后即可开 shell: 服务，以 shell 身份执行命令。
 *
 * 原始 APK 用的正是同一条路（com/byd/windowmanager/test/adb/AdbClient，连 127.0.0.1
 * 的 0x15b3=5555，开 "shell:"），区别只是它把作者自己的私钥内嵌了进去；本程序改为
 * 首次运行时自行生成密钥并走一次系统授权，见 {@link AdbKeyStore}。
 *
 * 已验证：
 *   TCP 连接成功 -> AUTH 通过 -> CNXN -> shell:id -> uid=2000(shell) context=u:r:shell:s0
 *
 * 协议要点（24 字节小端消息头：command, arg0, arg1, data_length, crc32, magic）：
 *   CNXN  data = "host::\0"
 *   AUTH  arg0=1 带 20 字节 token -> 以 arg0=2 回 256 字节签名
 *         arg0=3 表示服务端不认识本程序的公钥，需以 arg0=3 回送公钥；
 *         车机会弹「允许 USB 调试吗」，用户点允许后 adbd 会再发一个 token。
 *   OPEN  arg0=本地 id，data = 完整服务名 + "\0"（如 "shell:id"，**不是**裸命令）
 *   WRTE/OKAY/CLSE  数据、应答、结束
 *
 * shell 流走 v2（服务名 `shell,v2,raw:`），每一条 WRTE 的正文是若干"帧"，每帧定长头 5 字节：
 *
 *   [id:1][length:4 小端][payload:length]
 *
 * id 取值见 AOSP system/core/adb/shell_protocol.h 的 ShellProtocol::Id：
 * 0=stdin、1=stdout、2=stderr、3=exit（payload 是 1 字节退出码）、4=close-stdin、5=window-size。
 * 正文只在 id=1/2 时才是输出，且必须按 length 截取——头里那 4 字节不是正文。
 */
public final class AdbClient implements Closeable {

    private static final String TAG = "DashCastAdb";

    private static final int A_CNXN = 0x4e584e43; // "CNXN"
    private static final int A_AUTH = 0x48545541; // "AUTH"
    private static final int A_OPEN = 0x4e45504f; // "OPEN"
    private static final int A_OKAY = 0x59414b4f; // "OKAY"
    private static final int A_CLSE = 0x45534c43; // "CLSE"
    private static final int A_WRTE = 0x45545257; // "WRTE"

    private static final int A_VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 4096;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** SHA-1 的 PKCS#1 DigestInfo 前缀（RFC 8017），后面接 20 字节摘要。 */
    private static final byte[] SHA1_DIGEST_INFO_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14
    };

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 5555;

    /** 一次连接的结果分类，调用方据此决定是否弹授权引导。 */
    public enum State {
        /** 认证通过，可以执行 shell。 */
        READY,
        /** adbd 在线，但不认识本程序的公钥（AUTH arg0=3）——需要用户在车机上点「允许」。 */
        NEED_AUTHORIZATION,
        /** 连不上 127.0.0.1:PORT（adbd 没在 TCP 上监听，或网络异常）。 */
        UNREACHABLE,
        /** 其它失败（协议错误、超时等）。 */
        FAILED
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String banner;

    private AdbClient(Socket socket, InputStream in, OutputStream out, String banner) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.banner = banner;
    }

    public String banner() {
        return banner;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }

    // ---- 连接与认证 --------------------------------------------------------

    /**
     * 连接并进行 AUTH。
     *
     * requestAuthorization 决定本程序**允不允许触发车机的授权对话框**：
     *   false —— 只签名。签名被拒就报 NEED_AUTHORIZATION 并收手，绝不发送公钥。
     *   true  —— 签名被拒时发送公钥，让车机弹「允许 USB 调试吗」。
     *
     * 这个开关不是可有可无的：发公钥会弹出车机对话框，而那个对话框**绑在发起它的
     * 那条连接上**。若调用方随后超时断开（快速探测、开机路径都可能），窗口就变成
     * 一具收不到任何输入的孤儿，还会挡住后面真正需要点的对话框。所以只有能保证
     * "有人/有脚本会去点"的引导路径才允许把 requestAuthorization 置 true。
     */
    public static AdbClient connect(PrivateKey key, RSAPublicKey publicKey,
                                    long timeoutMs, boolean requestAuthorization,
                                    State[] outState) {
        State[] state = outState == null ? new State[1] : outState;
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(DEFAULT_HOST, DEFAULT_PORT), 5000);
            socket.setSoTimeout((int) timeoutMs);
        } catch (Throwable t) {
            state[0] = State.UNREACHABLE;
            closeQuietly(socket);
            return null;
        }

        boolean publicKeySent = false;
        int tokens = 0;
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            writeMsg(out, A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\0".getBytes(UTF8));

            for (int guard = 0; guard < 24; guard++) {
                Msg m = readMsg(in);
                if (m.command == A_CNXN) {
                    state[0] = State.READY;
                    Log.i(TAG, "认证通过：" + new String(m.data, UTF8).trim());
                    return new AdbClient(socket, in, out, new String(m.data, UTF8).trim());
                }
                if (m.command != A_AUTH) {
                    state[0] = State.FAILED;
                    break;
                }
                if (m.arg0 == 1) {
                    tokens++;
                    if (!requestAuthorization) {
                        // 只签名。连试三次都不认就收手，绝不让车机弹框。
                        if (tokens >= 3) {
                            state[0] = State.NEED_AUTHORIZATION;
                            Log.i(TAG, "签名未被接受；本次不允许触发授权框，交还给调用方");
                            break;
                        }
                        writeMsg(out, A_AUTH, 2, 0, signToken(key, m.data));
                    } else if (tokens >= 4 && !publicKeySent) {
                        // adbd 不认识本程序的签名时会一直重发 token。真实 adb 客户端的做法是
                        // 在这时**主动**把公钥送过去；adbd 收到 AUTH(arg0=3) 才会在车机上
                        // 弹出「允许 USB 调试吗」。只发一次，重复发会让弹框反复出现。
                        publicKeySent = true;
                        writeMsg(out, A_AUTH, 3, 0, AdbKeyStore.publicKeyPayload(publicKey));
                        state[0] = State.NEED_AUTHORIZATION;
                        Log.i(TAG, "签名未被接受，已发送公钥以请求系统授权");
                    } else {
                        writeMsg(out, A_AUTH, 2, 0, signToken(key, m.data));
                        if (publicKeySent) {
                            state[0] = State.NEED_AUTHORIZATION;
                        }
                    }
                } else if (m.arg0 == 3) {
                    state[0] = State.NEED_AUTHORIZATION;
                    // 只发一次：重复发送会让 adbd 反复弹框。
                    if (!publicKeySent) {
                        publicKeySent = true;
                        byte[] blob = AdbKeyStore.publicKeyPayload(publicKey);
                        writeMsg(out, A_AUTH, 3, 0, blob);
                    }
                } else {
                    state[0] = State.FAILED;
                    break;
                }
            }
            closeQuietly(socket);
            if (state[0] == null) {
                state[0] = publicKeySent ? State.NEED_AUTHORIZATION : State.FAILED;
            }
            return null;
        } catch (java.net.SocketTimeoutException e) {
            // 用户在车机上点「允许」需要时间；等超时不是失败，是"还差授权"。
            state[0] = publicKeySent ? State.NEED_AUTHORIZATION : State.FAILED;
            closeQuietly(socket);
            return null;
        } catch (Throwable t) {
            state[0] = publicKeySent ? State.NEED_AUTHORIZATION : State.FAILED;
            closeQuietly(socket);
            return null;
        }
    }

    /**
     * ADB 认证签名。**这一步极易写错**：
     *
     * adbd 发来的 20 字节 token 本身就是一个 SHA-1 摘要，客户端必须做
     *   RSA_sign(NID_sha1, token, 20, ...)
     * 即把 token 直接当作 digest 塞进 PKCS#1 v1.5 的 DigestInfo，**不能再哈希一次**。
     * JCA 的 "SHA1withRSA" 会先算 SHA1(token) 再签，必然验签失败（现象是 adbd 反复重发
     * 同一个 token 直到放弃）。所以这里手工拼 DigestInfo，用裸 RSA 加密。
     */
    static byte[] signToken(PrivateKey key, byte[] token) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(sha1DigestInfo(token));
    }

    /** token -> PKCS#1 v1.5 DigestInfo（SHA-1）。签名与自检共用，保证两边不会漂移。 */
    static byte[] sha1DigestInfo(byte[] token) {
        byte[] info = new byte[SHA1_DIGEST_INFO_PREFIX.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, info, 0, SHA1_DIGEST_INFO_PREFIX.length);
        System.arraycopy(token, 0, info, SHA1_DIGEST_INFO_PREFIX.length, token.length);
        return info;
    }

    // ---- shell -------------------------------------------------------------

    /**
     * shell v2 的帧头长度：1 字节 id + 4 字节小端 payload 长度。
     *
     * 这个 5 不是猜的：`echo ONE` 时 adbd 发来的一整条 WRTE 正文是
     *   01 04 00 00 00 4F 4E 45 0A 03 01 00 00 00 00
     * 拆开正好是两帧：id=1(stdout) len=4 "ONE\n"，id=3(exit) len=1 退出码 00。
     * 用固定长度输出再标定了一次长度字段的宽度：
     *   正文 9 字节  -> 09 00 00 00 + 9 字节      正文 21 字节 -> 15 00 00 00 + 21 字节
     *   正文 4093 字节 -> FD 0F 00 00 + 4093 字节（0x0FFD，4 字节小端，不是 1 字节）
     */
    private static final int SHELL_HEADER_BYTES = 5;

    /** 帧 id：只有这两个是命令的输出。 */
    private static final int SHELL_STDOUT = 1;
    private static final int SHELL_STDERR = 2;

    /** 帧 id=3：命令已退出，payload 是退出码。收到它就说明这条流逻辑上结束了。 */
    private static final int SHELL_EXIT = 3;

    /**
     * 协议里定义的 id 上限（5=window-size-change）。超出它只有一个解释：
     * 分帧位置偏了，读到的是正文。与其猜，不如报错。
     */
    private static final int SHELL_ID_MAX = 5;

    /**
     * 打开一条 shell 流用的服务名前缀。
     *
     * 必须是 `shell,v2,raw:`，不能用旧的 `shell:`：
     *
     *   - 旧服务名让 adbd 给命令挂一个 **pty**，命令于是变成"某个会话里的作业"，
     *     会话一回收就被连带清掉。`app_process … &` 在这种通道下只会在日志里
     *     留下一行重定向凭据，进程本身从不出现；而同一条命令走 PC 上的 `adb shell`
     *     （即 `shell,v2,raw:`）一次就成了。差别只在通道，不在命令。
     *   - `raw` 让 adbd 不分配 pty，子进程自成会话，后台任务才真正脱离 adb 会话。
     *   - v2 顺带把 stdout / stderr / 退出码分开送回，比 v1 混在一条流里更有信息量。
     *
     * 设备在 CNXN 里声明了 shell_v2（features 里含 "shell_v2"），所以这条路径一定可用。
     */
    private static final String SHELL_SERVICE = "shell,v2,raw:";

    /** 远端写入落盘的等待上限与轮询间隔（见 {@link #awaitRemoteSize}）。 */
    private static final long SETTLE_TIMEOUT_MS = 5000L;
    private static final long SETTLE_POLL_MS = 50L;

    private static byte[] shellServiceName(String command) {
        return (SHELL_SERVICE + command + "\0").getBytes(UTF8);
    }

    /**
     * shell 流的**流式**分帧器。存在两个必须跨报文保持状态的理由：
     *
     *   1. 一条 WRTE 里可以塞好几帧——40 字节输出是
     *      `01 09 00 00 00 A×9  01 15 00 00 00 A×21  01 03 00 00 00 A×3 …`
     *      一个 ADB 报文里连着好多个"头+正文"。只看第一帧会丢正文。
     *   2. 一帧也可以横跨两条 WRTE——40000 字节输出出现过
     *      `WRTE data=4096 … [id=1 len=3806] 只剩 3701 字节` 紧接
     *      `WRTE data=105`（正好是缺的 105 字节，且开头是正文不是帧头）。
     *      按报文各自对齐会在第二包上把正文当帧头读，从此彻底跑飞。
     *
     * 所以 id / 长度 / 正文余量都必须挂在对象上，不能是每报文一算的局部量。
     */
    private static final class ShellFrames {

        private final byte[] header = new byte[SHELL_HEADER_BYTES];
        private int headerBytes;
        private int id = -1;
        private int remaining;

        /**
         * 吃进一条 WRTE 的正文，把 stdout/stderr 追加到 body。
         *
         * @return true 表示已经收到 exit 帧，这条流读完了
         */
        boolean feed(byte[] data, int length, ByteArrayOutputStream body) throws IOException {
            int off = 0;
            while (off < length) {
                if (remaining > 0) {
                    int n = Math.min(remaining, length - off);
                    if (id == SHELL_STDOUT || id == SHELL_STDERR) {
                        body.write(data, off, n);
                    }
                    off += n;
                    remaining -= n;
                    if (remaining == 0 && id == SHELL_EXIT) {
                        return true;
                    }
                    continue;
                }
                int need = SHELL_HEADER_BYTES - headerBytes;
                int n = Math.min(need, length - off);
                System.arraycopy(data, off, header, headerBytes, n);
                headerBytes += n;
                off += n;
                if (headerBytes < SHELL_HEADER_BYTES) {
                    // 帧头本身也横跨了报文边界，等下一包补齐。
                    return false;
                }
                headerBytes = 0;
                id = header[0] & 0xFF;
                if (id > SHELL_ID_MAX) {
                    // 帧头位置读到的是正文，说明分帧已经跑飞。宁可报错也不能假装成功。
                    throw new IOException("shell 帧 id 非法：" + id);
                }
                remaining = (int) readUInt32LE(header, 1);
                if (remaining == 0 && id == SHELL_EXIT) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 执行一条命令并返回其 stdout/stderr 合并输出。超时返回已收到的部分。 */
    public String shell(String command, long timeoutMs) throws IOException {
        socket.setSoTimeout((int) timeoutMs);
        int localId = nextLocalId();
        writeMsg(out, A_OPEN, localId, 0, shellServiceName(command));

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ShellFrames frames = new ShellFrames();
        for (int guard = 0; guard < 100000; guard++) {
            Msg m;
            try {
                m = readMsg(in);
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
            // 同一条连接上先后开多条流：上一条流的收尾消息（CLSE，以及可能排在 CLSE
            // 之后才到的 exit 帧）还在缓冲区里。设备发来的消息 arg1 一定是**它的对端**
            // 也就是本端的 local id，所以不属于本条流的一律丢掉——不这么做，上一条流
            // 的残留会被当成本条流的输出。
            if (m.arg1 != localId) {
                continue;
            }
            if (m.command == A_WRTE) {
                boolean done = frames.feed(m.data, m.data.length, body);
                // OKAY 的 arg1 是**发 WRTE 那一端**的 id，也就是设备侧的 m.arg0。
                writeMsg(out, A_OKAY, localId, m.arg0, null);
                if (done) {
                    break;
                }
                continue;
            }
            if (m.command == A_CLSE) {
                break;
            }
            if (m.command != A_OKAY) {
                break;
            }
        }
        return new String(body.toByteArray(), UTF8);
    }

    /**
     * 把数据写进远端文件：开 `cat > <path>`，再把字节当作 stdin 灌进去。
     *
     * 这样 App 不需要任何可写共享目录——/data/local/tmp 只有 shell 能写，
     * 而这条通道本身就是 shell。
     *
     * 这条流故意留在 **v1（`shell:`）**，不跟 {@link #shell} 一起搬到 v2：
     * v1 的 WRTE 正文就是 stdin 本身，上传后 md5 与本地一致。
     * 换成 v2 并给每包加 kIdStdin 前缀后，远端 `cat` 只创建出 0 字节文件、
     * 调用一直不返回——原因未定位，而这条路径本来就没有 pty 问题（v1 的 pty
     * 只影响"命令把自己挂到后台"的用法），所以不做无根据的迁移。
     * 待办：查清 v2 下 stdin 的正确灌法后统一，见 docs/ADB_SELF_PROVISION_ZH.md 第 6 节。
     */
    public void writeRemoteFile(String remotePath, byte[] data, long timeoutMs) throws IOException {
        socket.setSoTimeout((int) timeoutMs);
        int localId = nextLocalId();
        Log.i(TAG, "writeRemoteFile " + remotePath + " " + data.length + "B localId=" + localId);
        writeMsg(out, A_OPEN, localId, 0,
                ("shell:cat > " + remotePath + "\0").getBytes(UTF8));

        int remoteId = -1;
        for (int i = 0; i < 16 && remoteId < 0; i++) {
            Msg m = readMsg(in);
            if (m.command == A_OKAY && m.arg1 == localId) {
                remoteId = m.arg0;
            } else if (m.command == A_CLSE && m.arg1 == localId) {
                throw new IOException("远端拒绝打开 shell:cat > " + remotePath);
            }
        }
        if (remoteId < 0) {
            throw new IOException("等待 OKAY 超时");
        }

        int offset = 0;
        while (offset < data.length) {
            int chunk = Math.min(MAX_PAYLOAD, data.length - offset);
            byte[] slice = new byte[chunk];
            System.arraycopy(data, offset, slice, 0, chunk);
            writeMsg(out, A_WRTE, localId, remoteId, slice);
            offset += chunk;

            // cat 会持续消费，但仍要处理夹在中间的控制消息，避免管道反压死锁。
            while (in.available() > 0) {
                Msg m = readMsg(in);
                if (m.command == A_CLSE && m.arg1 == localId) {
                    throw new IOException("远端在写入中途关闭");
                }
            }
        }

        // 关掉 stdin 让 cat 退出。
        writeMsg(out, A_CLSE, localId, remoteId, null);
        for (int i = 0; i < 60; i++) {
            try {
                Msg m = readMsg(in);
                if (m.command == A_CLSE && m.arg1 == localId) {
                    break;
                }
                if (m.command == A_WRTE) {
                    writeMsg(out, A_OKAY, localId, m.arg1, null);
                }
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
        }

        // CLSE 是**双向**的：这一条只是 adbd 对客户端关闭请求的应答，不代表远端的
        // cat 已经把缓冲区落盘。返回后立刻 `wc -c` 会读到 0，几十毫秒后才变成
        // 完整长度——调用方若在这段窗口里回读，会误判成"上传失败"，而文件其实
        // 是好的。所以"写完"必须由这里自己等出来，不能交给调用方去赌时序。
        awaitRemoteSize(remotePath, data.length, SETTLE_TIMEOUT_MS);
        // 长度对不代表内容对：远端有"长度正好、内容全是 0"的文件。长度判据会一路
        // 放行，代理于是永远起不来。这里让页缓存落盘，再按内容核对。
        syncRemote();
        verifyRemoteDigest(remotePath, sha256Hex(data));
        Log.i(TAG, "writeRemoteFile 完成 " + remotePath + " " + data.length + "B");
    }

    /**
     * 远端文件的 SHA-256（小写 hex）。
     *
     * 判据必须是内容。此前整条链上只有"长度"，而掉电后丢数据留下的正是"长度正确、
     * 内容全 0"——这种文件骗过了每一道检查。
     */
    static String sha256Hex(byte[] data) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("缺少 SHA-256 实现", e);
        }
    }

    /**
     * 把设备页缓存刷到介质。`sync` 是 toybox 的原生调用。
     *
     * 为什么需要：写文件的不是本进程时（例如系统框架把授权写进 /data/misc/adb/adb_keys），
     * 应用没法对它的 fd 做 fsync；但掉电前只要有人 sync 过一次，那份追加就落住了。
     */
    void syncRemote() {
        try {
            shell("/system/bin/sync", 10000L);
        } catch (IOException e) {
            Log.w(TAG, "sync 失败（不影响本次写入，只影响掉电后的留存）：" + e);
        }
    }

    /** 回读远端内容摘要并与源比对；不一致即抛错，不放过。 */
    private void verifyRemoteDigest(String remotePath, String expected) throws IOException {
        String out = shell("sha256sum " + remotePath + " 2>/dev/null", 15000L).trim();
        int sp = out.indexOf(' ');
        String actual = (sp < 0 ? out : out.substring(0, sp)).trim();
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IOException("远端文件内容与源不一致：" + remotePath
                    + " 期望 sha256=" + expected + "，实际「" + actual + "」");
        }
    }

    /**
     * 等到远端文件长度稳定成期望值。
     *
     * 这是等待一个**异步写入落盘**的收敛条件，不是重试掩盖错误：超时即抛错，
     * 长度不对也抛错。两者都不可吞。
     */
    private void awaitRemoteSize(String remotePath, long expected, long timeoutMs)
            throws IOException {
        long deadline = System.nanoTime() + timeoutMs * 1000000L;
        long last = -1L;
        while (true) {
            last = remoteSize(remotePath);
            if (last == expected) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new IOException("远端文件写入未完成：" + remotePath
                        + " 期望 " + expected + " 字节，实际 " + last);
            }
            sleep(SETTLE_POLL_MS);
        }
    }

    /** 远端文件字节数；读不到返回 -1。 */
    private long remoteSize(String remotePath) {
        String out;
        try {
            out = shell("wc -c < " + remotePath + " 2>/dev/null", 5000L).trim();
        } catch (IOException e) {
            return -1L;
        }
        if (out.isEmpty()) {
            return -1L;
        }
        int nl = out.indexOf('\n');
        if (nl >= 0) {
            out = out.substring(0, nl);
        }
        try {
            return Long.parseLong(out.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int sLocalId = 1;

    private static synchronized int nextLocalId() {
        return sLocalId++;
    }

    // ---- 报文编解码 --------------------------------------------------------

    private static final class Msg {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new IOException("连接在 " + off + "/" + n + " 字节处结束");
            }
            off += r;
        }
        return buf;
    }

    private static Msg readMsg(InputStream in) throws IOException {
        ByteBuffer head = ByteBuffer.wrap(readExactly(in, 24)).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.command = head.getInt();
        m.arg0 = head.getInt();
        m.arg1 = head.getInt();
        int len = head.getInt();
        head.getInt(); // data_crc32：现代 adbd 不校验
        int magic = head.getInt();
        int expect = m.command ^ 0xFFFFFFFF;
        if (magic != expect) {
            throw new IOException("magic 不符：got 0x" + Integer.toHexString(magic)
                    + " expect 0x" + Integer.toHexString(expect));
        }
        if (len < 0 || len > MAX_PAYLOAD * 4) {
            throw new IOException("data_length 异常：" + len);
        }
        m.data = len > 0 ? readExactly(in, len) : new byte[0];
        return m;
    }

    private static void writeMsg(OutputStream out, int command, int arg0, int arg1, byte[] data)
            throws IOException {
        int len = data == null ? 0 : data.length;
        ByteBuffer bb = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(command);
        bb.putInt(arg0);
        bb.putInt(arg1);
        bb.putInt(len);
        bb.putInt(0);
        bb.putInt(command ^ 0xFFFFFFFF);
        if (len > 0) {
            bb.put(data);
        }
        out.write(bb.array());
        out.flush();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }

    /** 供 AdbKeyStore 复用的 32 位小端整数读取。 */
    static long readUInt32LE(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    /** 供 AdbKeyStore 复用：BigInteger 的固定长度小端字节。 */
    static byte[] toLittleEndian(BigInteger v, int length) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[length];
        // be 是补码大端（可能带前导 0x00），逐字节反转到小端。
        for (int i = 0; i < be.length && i < length; i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }
}
