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
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;

import javax.crypto.Cipher;

/**
 * ADB 线协议客户端。存在的唯一理由：**让 App 自己拿到 uid 2000**。
 *
 * 为什么需要它：这台车机上
 *   - `setLaunchDisplayId(2)` 从普通应用发起会被 AMS 拒绝
 *     （实测 SecurityException: Permission Denial ... with launchDisplayId=2），
 *   - 输入注入需要 INJECT_EVENTS，是 signature|privileged。
 * 两者都只有 uid 2000(shell) 能做。而 adbd 就监听在本机 127.0.0.1:5555，
 * 通过 AUTH 之后即可开 shell: 服务，以 shell 身份执行命令。
 *
 * 原始 APK 用的正是同一条路（com/byd/windowmanager/test/adb/AdbClient，连 127.0.0.1
 * 的 0x15b3=5555，开 "shell:"），区别只是它把作者自己的私钥内嵌了进去；我们改为
 * 首次运行时本机自生成密钥并走一次系统授权，见 {@link AdbKeyStore}。
 *
 * 已在本机实测通过（探针 com.byd.dashcast.probe，uid 10100）：
 *   TCP 连接成功 -> AUTH 通过 -> CNXN -> shell:id -> uid=2000(shell) context=u:r:shell:s0
 *
 * 协议要点（24 字节小端消息头：command, arg0, arg1, data_length, crc32, magic）：
 *   CNXN  data = "host::\0"
 *   AUTH  arg0=1 带 20 字节 token -> 以 arg0=2 回 256 字节签名
 *         arg0=3 表示服务端不认识我们的公钥，需以 arg0=3 回送公钥；
 *         车机会弹「允许 USB 调试吗」，用户点允许后 adbd 会再发一个 token。
 *   OPEN  arg0=本地 id，data = 完整服务名 + "\0"（如 "shell:id"，**不是**裸命令）
 *   WRTE/OKAY/CLSE  数据、应答、结束
 *
 * 未实现 shell_v2：我们不在 CNXN 里声明 shell_v2 feature，adbd 会退回 v1（原始字节流）。
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
    /**
     * {@code A_CNXN} 里声明的 maxdata，即"adbd 一次最多发给我多少字节"。
     *
     * <p>**这是 170 KB 级抓帧流的吞吐命门。** 声明 4096 时 adbd 每包只发 4 KB，
     * 一帧 PNG 要拆成 42 个 {@code A_WRTE}，而 ADB 协议**每个包都要回一次 {@code A_OKAY}**
     * —— 帧时间被 42 次往返吃掉。实测症状很典型：App 进程 CPU 只有 4.4%（8 核机器），
     * 帧率却卡在 3 fps，也就是"根本没在算，全在等"。
     *
     * <p>标准 adb 客户端声明的是 1 MB（AOSP {@code MAX_PAYLOAD}），这里对齐。
     * adbd 取两者较小值，所以声明大了不会被拒。
     */
    private static final int MAX_PAYLOAD = 1024 * 1024;
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
        /** adbd 在线，但不认识我们的公钥（AUTH arg0=3）——需要用户在车机上点「允许」。 */
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
     * requestAuthorization 决定我们**允不允许触发车机的授权对话框**：
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
                        // adbd 不认识我们的签名时会一直重发 token。真实 adb 客户端的做法是
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
     * shell 协议 v2 的包头：{@code [Id:1][length:4 小端]}，其后才是数据。
     *
     * <p>定义见 AOSP {@code shell_service_protocol.cpp}：
     * <pre>
     * bool ShellProtocol::Write(Id id, size_t length) {
     *     buffer_[0] = id;
     *     length_t typed_length = length;
     *     memcpy(&amp;buffer_[1], &amp;typed_length, sizeof(typed_length));
     * </pre>
     */
    private static final int SHELL_V2_HEADER = 5;

    /** 包头的 {@code Id}：命令的 stdout / stderr。其余（退出码、关 stdin、窗口变化）不是正文。 */
    private static final int SHELL_STDOUT = 1;
    private static final int SHELL_STDERR = 2;

    /**
     * 打开一条 shell 流用的服务名前缀。
     *
     * 必须是 `shell,v2,raw:`，不能用旧的 `shell:`：
     *
     *   - 旧服务名让 adbd 给命令挂一个 **pty**，命令于是变成"某个会话里的作业"，
     *     会话一回收就被连带清掉。实测 `app_process … &` 在这种通道下只会在日志里
     *     留下一行重定向凭据，进程本身从不出现；而同一条命令走 PC 上的 `adb shell`
     *     （即 `shell,v2,raw:`）一次就成了。差别只在通道，不在命令。
     *   - `raw` 让 adbd 不分配 pty，子进程自成会话，后台任务才真正脱离 adb 会话。
     *   - v2 顺带把 stdout / stderr / 退出码分开送回，比 v1 混在一条流里更有信息量。
     *
     * 设备在 CNXN 里声明了 shell_v2（features 里含 "shell_v2"），所以这条路径一定可用。
     */
    private static final String SHELL_SERVICE = "shell,v2,raw:";

    /**
     * {@code exec:} 服务：不经过 shell/pty，stdout 二进制透明。
     * 这正是 {@code adb exec-out} 走的路，也是这台设备上唯一能完整取回二进制的通道
     * （{@code shell,v2,raw:} 实测会在中途断掉，PNG 只回来 6 字节）。
     * 代价是**不能用管道、重定向等 shell 语法**，只能是一条命令。
     */
    private static final String EXEC_SERVICE = "exec:";

    /** 执行一条命令并返回其 stdout/stderr 合并输出。超时返回已收到的部分。 */
    public String shell(String command, long timeoutMs) throws IOException {
        return new String(openStream(SHELL_SERVICE, command, timeoutMs, true), UTF8);
    }

    /**
     * 走 {@code exec:} 服务取回**二进制**输出（相当于 {@code adb exec-out}）。
     *
     * <p>这是本设备上唯一能把 PNG 完整拿回来的通道：{@code shell,v2,raw:} 实测会中途
     * 断掉（{@code screencap -p} 只回来 6 字节），base64 绕行同样被截断。
     * 限制是**不能带管道或重定向**，只能是单条命令。
     */
    public byte[] exec(String command, long timeoutMs) throws IOException {
        return openStream(EXEC_SERVICE, command, timeoutMs, false);
    }

    /**
     * 开一条服务流并读回全部输出。
     *
     * @param stripStreamId true 表示服务用 **shell 协议 v2**（{@code shell,v2,...:}），
     *                      正文有 5 字节包头；false 表示 {@code exec:} 这类不带封装的
     *                      （协议 v0），正文即数据。两者必须分开处理。
     */
    private byte[] openStream(String service, String command, long timeoutMs,
            boolean stripStreamId) throws IOException {
        socket.setSoTimeout((int) timeoutMs);
        int localId = nextLocalId();
        writeMsg(out, A_OPEN, localId, 0, (service + command + "\0").getBytes(UTF8));

        int remoteId = -1;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ShellV2Parser shell = stripStreamId ? new ShellV2Parser() : null;
        for (int guard = 0; guard < 100000; guard++) {
            Msg m;
            try {
                m = readMsg(in);
            } catch (java.net.SocketTimeoutException e) {
                Log.w(TAG, "openStream[" + service + "]: 读超时，已收 " + body.size() + " 字节");
                break;
            }
            if (m.command == A_OKAY) {
                // 流建立确认：arg0 是我们的 localId，arg1 是 adbd 给这条流分配的 id。
                if (m.arg0 == localId) {
                    remoteId = m.arg1;
                }
                continue;
            }
            if (m.command == A_WRTE) {
                // **必须按 m.arg1 过滤**：adbd 发来的 WRTE 里 arg0 是它的 local id、
                // arg1 才是我们的 localId。同一条 socket 上会并发多条流（心跳巡检、
                // 判页抓帧各开一条），不过滤就会把别的流的字节混进本次结果——
                // 实测表现是"PNG 只回来 13 字节 / 偶发 62 字节"这种怪象。
                if (m.arg1 == localId) {
                    if (m.data.length > 0) {
                        if (shell != null) {
                            shell.feed(m.data);
                        } else {
                            body.write(m.data, 0, m.data.length);
                        }
                    }
                    // 确认时远程 id 是 m.arg0（adbd 的 local id），不是 m.arg1。
                    remoteId = m.arg0;
                    writeMsg(out, A_OKAY, localId, remoteId, null);
                }
                continue;
            }
            if (m.command == A_CLSE) {
                // 同一条连接上会先后开多条流，CLSE 的 arg1 才是本地 id。
                // 不比对就会把上一条流的关闭消息当成自己的，误判为"服务被拒"。
                if (m.arg1 == localId) {
                    Log.i(TAG, "openStream[" + service + "]: 远端关闭，已收 "
                            + (shell != null ? shell.size() : body.size()) + " 字节");
                    break;
                }
                continue;
            }
            Log.w(TAG, "openStream[" + service + "]: 收到非预期命令 0x"
                    + Integer.toHexString(m.command) + "，已收 "
                    + (shell != null ? shell.size() : body.size()) + " 字节");
            break;
        }
        // 收尾必须回 CLSE：不回的话 adbd 认为这条流还开着，会继续往里灌残留数据，
        // 后面的流就会一直被这些垃圾包干扰。
        if (remoteId >= 0) {
            try {
                writeMsg(out, A_CLSE, localId, remoteId, null);
            } catch (IOException e) {
                Log.w(TAG, "openStream[" + service + "]: 发送 CLSE 失败", e);
            }
        }
        return shell != null ? shell.body() : body.toByteArray();
    }

    /**
     * shell 协议 v2 的流式解析器。
     *
     * <p>帧格式是 {@code [Id:1][length:4 小端][data]}（见 AOSP {@code shell_service_protocol.cpp}
     * 的 {@code ShellProtocol::Write}）。**关键事实：一个 ADB WRTE 里可能并排多个帧。**
     * 实测 adbd 把 {@code cmd package query-activities} 的 18428 字节输出压成了
     * 8192+8192+2044 三帧，塞进同一条 18443 字节的 WRTE：
     * <pre>
     *   PROBE 包#0 bytes=18443 id=1 hdr=8192
     *   18443 == (5+8192) + (5+8192) + (5+2044)
     * </pre>
     * 所以既不能"一个 WRTE 当一个帧"（会只剩第一帧），也不能只跳过 Id 那 1 字节
     * （会把 length 当正文，每个帧给结果前头多塞 4 字节）。
     *
     * <p>帧还可能跨 WRTE 边界，所以尾部残片要留到下一次 {@link #feed}。
     */
    private static final class ShellV2Parser {

        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        /** 上一包尾部那半帧，等下一包补齐。 */
        private byte[] pending = new byte[0];

        void feed(byte[] packet) {
            byte[] buf;
            if (pending.length == 0) {
                buf = packet;
            } else {
                buf = new byte[pending.length + packet.length];
                System.arraycopy(pending, 0, buf, 0, pending.length);
                System.arraycopy(packet, 0, buf, pending.length, packet.length);
                pending = new byte[0];
            }
            int off = 0;
            while (buf.length - off >= SHELL_V2_HEADER) {
                int id = buf[off] & 0xFF;
                int length = (buf[off + 1] & 0xFF)
                        | ((buf[off + 2] & 0xFF) << 8)
                        | ((buf[off + 3] & 0xFF) << 16)
                        | ((buf[off + 4] & 0xFF) << 24);
                if (length < 0 || buf.length - off - SHELL_V2_HEADER < length) {
                    // 这一帧还没到齐，留给下一次 feed。
                    break;
                }
                if (id == SHELL_STDOUT || id == SHELL_STDERR) {
                    body.write(buf, off + SHELL_V2_HEADER, length);
                }
                // kIdExit / kIdCloseStdin / kIdWindowSizeChange：不是正文，只跳过。
                off += SHELL_V2_HEADER + length;
            }
            if (off < buf.length) {
                byte[] rest = new byte[buf.length - off];
                System.arraycopy(buf, off, rest, 0, rest.length);
                pending = rest;
            }
        }

        int size() {
            return body.size();
        }

        byte[] body() {
            return body.toByteArray();
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
