import java.io.BufferedInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * Adds sparse, zero-filled capacity to an owned CodeQL string-pool array and
 * updates its ordinary CRC metadata. Logical contents and maxSet are unchanged.
 */
public final class PoolPad {
  private static long crc32(Path path) throws Exception {
    CRC32 crc = new CRC32();
    byte[] buffer = new byte[1024 * 1024];
    try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(path))) {
      int count;
      while ((count = input.read(buffer)) >= 0) {
        if (count != 0) crc.update(buffer, 0, count);
      }
    }
    return crc.getValue();
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("usage: FOLDER TOTAL_BYTES FIRST_PAGE_BYTES");
    }
    Path folder = Path.of(args[0]);
    long totalBytes = Long.parseLong(args[1]);
    long firstPageBytes = Long.parseLong(args[2]);
    if ((totalBytes & 4095L) != 0
        || (firstPageBytes & 4095L) != 0
        || firstPageBytes < 8192
        || firstPageBytes > totalBytes) {
      throw new IllegalArgumentException("sizes must be page aligned and ordered");
    }

    byte[] originalInfo = Files.readAllBytes(folder.resolve("info"));
    ByteBuffer original = ByteBuffer.wrap(originalInfo).order(ByteOrder.BIG_ENDIAN);
    long version = original.getLong(0);
    long files = original.getLong(8);
    long maxSet = original.getLong(16);
    if (version != 1 || files != 1 || originalInfo.length != 40) {
      throw new IllegalArgumentException("expected a fresh one-page pool array");
    }

    long secondPageBytes = totalBytes - firstPageBytes;
    if (secondPageBytes <= 0 || secondPageBytes > (1L << 30)) {
      throw new IllegalArgumentException("expected exactly two sparse pages");
    }
    Path first = folder.resolve("page-000000");
    Path second = folder.resolve("page-000001");
    try (RandomAccessFile file = new RandomAccessFile(first.toFile(), "rw")) {
      file.setLength(firstPageBytes);
    }
    try (RandomAccessFile file = new RandomAccessFile(second.toFile(), "rw")) {
      file.setLength(secondPageBytes);
    }

    byte[] replacementInfo = new byte[48];
    ByteBuffer replacement = ByteBuffer.wrap(replacementInfo).order(ByteOrder.BIG_ENDIAN);
    replacement.putLong(version);
    replacement.putLong(2);
    replacement.putLong(maxSet);
    replacement.putLong(crc32(first));
    replacement.putLong(crc32(second));
    CRC32 infoCrc = new CRC32();
    infoCrc.update(replacementInfo, 0, replacementInfo.length - Long.BYTES);
    replacement.putLong(infoCrc.getValue());
    Files.write(folder.resolve("info"), replacementInfo);
    System.out.printf(
        "HOSTED_POOL_SHAPE total=0x%x first=0x%x second=0x%x max_set=0x%x%n",
        totalBytes, firstPageBytes, secondPageBytes, maxSet);
  }
}
