package kawaii.viey.browser;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * .part 文件：块位图、下载写入、分块（9.72MB part）校验、续传状态。
 */
public class PartFile {

    public final File file;
    public final long size;
    public final int chunkCount;
    public final int blockCount;
    private final BitSet done = new BitSet();
    private final Set<Integer> reserved = new HashSet<>();
    private final Object lock = new Object();
    private final File state;

    public PartFile(Context c, String name, long size) {
        File dir = dir(c);
        this.file = new File(dir, safe(name) + ".part");
        this.state = new File(dir, safe(name) + ".part.state");
        this.size = size;
        this.chunkCount = (int) Math.max(1, (size + Ed2kHash.CHUNK - 1) / Ed2kHash.CHUNK);
        this.blockCount = (int) Math.max(1, (size + Ed2kProto.BLOCK - 1) / Ed2kProto.BLOCK);
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "ed2k");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String safe(String n) {
        if (n == null || n.length() == 0) return "download";
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public boolean loadState() {
        try {
            if (!state.exists()) return false;
            String s = PluginStore.read(state);
            JSONObject o = new JSONObject(s);
            JSONArray arr = o.optJSONArray("done");
            if (arr == null) return false;
            for (int i = 0; i < arr.length(); i++) done.set(arr.getInt(i));
            return done.cardinality() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public void saveState() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (lock) {
                for (int i = done.nextSetBit(0); i >= 0; i = done.nextSetBit(i + 1)) arr.put(i);
            }
            JSONObject o = new JSONObject();
            o.put("done", arr);
            o.put("size", size);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(state);
            fos.write(o.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    /** 预分配文件，避免写时反复扩展 */
    public void prepare() throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            if (raf.length() != size) raf.setLength(size);
        }
    }

    public boolean isDone(int block) {
        synchronized (lock) {
            return done.get(block);
        }
    }

    public boolean claim(int block) {
        synchronized (lock) {
            if (done.get(block) || reserved.contains(block)) return false;
            reserved.add(block);
            return true;
        }
    }

    public void release(int block) {
        synchronized (lock) {
            reserved.remove(block);
        }
    }

    public void writeBlock(int block, long start, byte[] data) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(start);
            raf.write(data);
        }
        synchronized (lock) {
            done.set(block);
            reserved.remove(block);
        }
    }

    public int doneBlocks() {
        synchronized (lock) {
            return done.cardinality();
        }
    }

    public int reserveCount() {
        synchronized (lock) {
            return reserved.size();
        }
    }

    public boolean isComplete() {
        return doneBlocks() >= blockCount;
    }

    public BitSet snapshot() {
        synchronized (lock) {
            return (BitSet) done.clone();
        }
    }

    /** 校验每个 chunk（不足一个 chunk 的按实际长度算），返回失败的 chunk 下标 */
    public List<Integer> verifyChunks(List<String> expected) throws Exception {
        List<Integer> bad = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            if (i >= expected.size() && expected.size() > 0) break;
            String actual = Ed2kHash.hashChunk(file, i);
            if (expected.size() > i && !expected.get(i).equalsIgnoreCase(actual)) bad.add(i);
        }
        return bad;
    }

    /** 把某个 chunk 内的块标为未完成（AICH 校验/ed2k 校验失败后的恢复入口） */
    public void invalidateChunk(int chunk) {
        int from = (int) ((long) chunk * Ed2kHash.CHUNK / Ed2kProto.BLOCK);
        int to = (int) Math.min(blockCount, ((long) (chunk + 1) * Ed2kHash.CHUNK + Ed2kProto.BLOCK - 1) / Ed2kProto.BLOCK);
        synchronized (lock) {
            done.clear(from, to);
            reserved.clear();
        }
    }

    public long startOf(int block) {
        return (long) block * Ed2kProto.BLOCK;
    }

    public int lenOf(int block) {
        return (int) Math.min(Ed2kProto.BLOCK, size - startOf(block));
    }

    /** 完整文件改名为最终文件名并返回 */
    public File finish(String finalName) {
        File out = new File(file.getParentFile(), safe(finalName));
        if (out.exists()) out.delete();
        if (file.renameTo(out)) {
            state.delete();
            return out;
        }
        return file;
    }

    public void delete() {
        file.delete();
        state.delete();
    }
}
