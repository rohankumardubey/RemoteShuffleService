/*
 * Copyright (c) 2020 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.rss.tools;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;
import com.uber.rss.common.Compression;
import com.uber.rss.exceptions.RssInvalidDataException;
import com.uber.rss.util.ByteBufUtils;
import com.uber.rss.util.StreamUtils;
import io.airlift.compress.zstd.ZstdDecompressor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.io.FileInputStream;
import java.io.InputStream;

/***
 * This tool checks shuffle partition file.
 */
public class PartitionFileChecker {
  private String filePath;
  private String fileCompressCodec = Compression.COMPRESSION_CODEC_LZ4;
  private String blockCompressCodec = Compression.COMPRESSION_CODEC_LZ4;

  public void run() {
    ByteBuf dataBlockStreamData = Unpooled.buffer(1000);
    ByteBuf dataBlockStreamUncompressedData = dataBlockStreamData;

    // Read data block stream from file
    try (FileInputStream fileInputStream = new FileInputStream(filePath)) {
      InputStream inputStream = fileInputStream;
      if (fileCompressCodec.equals(Compression.COMPRESSION_CODEC_LZ4)) {
        inputStream = new LZ4BlockInputStream(fileInputStream);
      } else if (fileCompressCodec.equals(Compression.COMPRESSION_CODEC_ZSTD)) {
        inputStream = new ZstdInputStream(fileInputStream);
      }
      while (true) {
        byte[] bytes = StreamUtils.readBytes(inputStream, Long.BYTES);
        if (bytes == null) {
          break;
        }
        long taskAttemptId = ByteBufUtils.readLong(bytes, 0);
        bytes = StreamUtils.readBytes(inputStream, Integer.BYTES);
        int dataBlockLength = ByteBufUtils.readInt(bytes, 0);
        byte[] dataBlockBytes = StreamUtils.readBytes(inputStream, dataBlockLength);
        dataBlockStreamData.writeBytes(dataBlockBytes);
        System.out.println(String.format("Got data block from task attempt %s, %s bytes", taskAttemptId, dataBlockLength));
      }
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }

    if (blockCompressCodec.equals(Compression.COMPRESSION_CODEC_LZ4)) {
      dataBlockStreamUncompressedData = Unpooled.buffer(1000);

      LZ4FastDecompressor decompressor = LZ4Factory.fastestInstance().fastDecompressor();

      while (dataBlockStreamData.readableBytes() > 0) {
        int compressedLen = dataBlockStreamData.readInt();
        int uncompressedLen = dataBlockStreamData.readInt();
        byte[] compressedBytes = new byte[compressedLen];
        byte[] uncompressedBytes = new byte[uncompressedLen];
        dataBlockStreamData.readBytes(compressedBytes);
        decompressor.decompress(compressedBytes, uncompressedBytes);
        dataBlockStreamUncompressedData.writeBytes(uncompressedBytes);
      }
    } else if (blockCompressCodec.equals(Compression.COMPRESSION_CODEC_ZSTD)) {
      dataBlockStreamUncompressedData = Unpooled.buffer(1000);
      while (dataBlockStreamData.readableBytes() > 0) {
        int compressedLen = dataBlockStreamData.readInt();
        int uncompressedLen = dataBlockStreamData.readInt();
        byte[] compressedBytes = new byte[compressedLen];
        byte[] uncompressedBytes = new byte[uncompressedLen];
        dataBlockStreamData.readBytes(compressedBytes);
        long decompressResult = Zstd.decompress(compressedBytes, uncompressedBytes);
        if (Zstd.isError(decompressResult)) {
          throw new RssInvalidDataException("Failed to decompress zstd data, returned value: " + decompressResult);
        }
        dataBlockStreamUncompressedData.writeBytes(uncompressedBytes);
      }
    }

    while (dataBlockStreamUncompressedData.readableBytes() > 0) {
      int keyLen = dataBlockStreamUncompressedData.readInt();
      if (keyLen > 0) {
        byte[] keyBytes = new byte[keyLen];
        dataBlockStreamUncompressedData.readBytes(keyBytes);
      }
      int valueLen = dataBlockStreamUncompressedData.readInt();
      if (valueLen > 0) {
        byte[] valueBytes = new byte[valueLen];
        dataBlockStreamUncompressedData.readBytes(valueBytes);
      }
    }
  }

  public static void main(String[] args) {
    PartitionFileChecker tool = new PartitionFileChecker();

    int i = 0;
    while (i < args.length) {
      String argName = args[i++];
      if (argName.equalsIgnoreCase("-file")) {
        tool.filePath = args[i++];
      } else {
        throw new IllegalArgumentException("Unsupported argument: " + argName);
      }
    }

    tool.run();
  }
}
