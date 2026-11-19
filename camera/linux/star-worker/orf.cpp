#include "orf.h"

#if defined(__arm__) && defined(__ARM_NEON) && \
    !defined(OM1_DISABLE_NEON_BITSTREAM) && \
    !defined(OM1_DISABLE_PARALLEL_CODEC)
#include "syscalls.h"
#include "thread_pipeline.h"
#define OM1_PARALLEL_CODEC 1
#endif

#include "util.h"

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace om1_stars {
namespace {

// Ordinary OM-1 files place the compressed RAW strip after a much larger
// metadata/preview region.  Only the directory structures and values needed
// by this decoder are retained; the rest is consumed forward exactly once.
// P8153926, for example, starts its strip at 1,817,600 bytes while every
// required value is near the beginning of the prefix.
constexpr uint32_t kMetadataPrefixCapacity = 64u * 1024u;
constexpr uint32_t kMaximumMetadataExtent = 2u * 1024u * 1024u;
#if defined(OM1_PAYLOAD_READ_CHUNK)
constexpr uint32_t kPayloadReadChunk = OM1_PAYLOAD_READ_CHUNK;
#else
constexpr uint32_t kPayloadReadChunk = 32u * 1024u;
#endif
static_assert(kPayloadReadChunk >= 9u &&
                  kPayloadReadChunk <= kMetadataPrefixCapacity,
              "payload read chunk must fit the static stream buffer");
constexpr uint16_t kTiffByte = 1;
constexpr uint16_t kTiffAscii = 2;
constexpr uint16_t kTiffShort = 3;
constexpr uint16_t kTiffLong = 4;
constexpr uint16_t kTiffRational = 5;
constexpr uint16_t kTiffUndefined = 7;
constexpr uint16_t kTiffIfd = 13;

// This buffer first holds the bounded metadata working prefix.  Once metadata
// has been copied into RawInfo and the remaining prefix has been discarded,
// it becomes the compressed-strip input buffer.
// Eight guard bytes permit one final zero-padded 72-bit code-pair lookahead;
// consumed bits are still checked against the actual strip boundary.
uint8_t g_prefix[kMetadataPrefixCapacity + 8u];
uint32_t g_metadata_prefix_loaded;
uint16_t g_rows[2][kMaxRawWidth];
uint32_t g_reduced_sums[kMaxDetectorWidth];
uint8_t g_reduced_saturated[kMaxDetectorWidth];

#if defined(OM1_PARALLEL_CODEC)
constexpr uint32_t kResidualRingRows = 16u;
constexpr uint32_t kCodecThreadStackBytes = 32u * 1024u;

struct alignas(64) ResidualRowSlot {
  // Keep the streamed row cache-line aligned.  The producer also publishes
  // the two refinement histories that seed columns 2 and 3.
  int32_t values[kMaxRawWidth];
  int32_t initial_histories[2];
};

struct alignas(64) ParallelCodecState {
  alignas(64) uint32_t produced;
  alignas(64) uint32_t consumed;
  alignas(64) uint32_t data_event;
  alignas(64) uint32_t space_event;
  alignas(64) uint32_t data_waiting;
  alignas(64) uint32_t space_waiting;
  alignas(64) uint32_t input_done;
  alignas(64) uint32_t cancelled;
  alignas(64) uint32_t worker_failed;
  RawInfo info;
  ReducedRowConsumer consumer;
  void *consumer_context;
  DecodeStats *stats;
  Error worker_error;
  ResidualRowSlot rows[kResidualRingRows];
};

ParallelCodecState g_parallel_codec;
FixedThread g_codec_thread;
alignas(16) uint8_t g_codec_thread_stack[kCodecThreadStackBytes];
#endif

struct __attribute__((packed, may_alias)) PackedU32 {
  uint32_t value;
};

struct SchemaEntry {
  uint16_t tag;
  uint16_t type;
  uint32_t count;
};

constexpr SchemaEntry kIfd0Schema[] = {
    {0x0100, kTiffLong, 1},      {0x0101, kTiffLong, 1},
    {0x0102, kTiffShort, 1},     {0x0103, kTiffShort, 1},
    {0x0106, kTiffShort, 1},     {0x010e, kTiffAscii, 32},
    {0x010f, kTiffAscii, 24},    {0x0110, kTiffAscii, 17},
    {0x0111, kTiffLong, 1},      {0x0112, kTiffShort, 1},
    {0x0115, kTiffShort, 1},     {0x0116, kTiffLong, 1},
    {0x0117, kTiffLong, 1},      {0x011a, kTiffRational, 1},
    {0x011b, kTiffRational, 1},  {0x011c, kTiffShort, 1},
    {0x0128, kTiffShort, 1},     {0x0131, kTiffAscii, 32},
    {0x0132, kTiffAscii, 20},    {0x013b, kTiffAscii, 64},
    {0x02bc, kTiffByte, 2301},   {0x4746, kTiffShort, 1},
    {0x8298, kTiffAscii, 64},    {0x8769, kTiffLong, 1},
    {0x8825, kTiffLong, 1},
};

struct Ifd {
  uint32_t offset;
  uint32_t base;
  uint32_t limit;
  uint16_t count;
};

struct Entry {
  uint32_t offset;
  uint32_t base;
  uint32_t limit;
  uint16_t tag;
  uint16_t type;
  uint32_t count;
};

uint16_t load_u16(uint32_t offset) {
  return static_cast<uint16_t>(g_prefix[offset]) |
         static_cast<uint16_t>(static_cast<uint16_t>(g_prefix[offset + 1u])
                               << 8);
}

uint32_t load_u32(uint32_t offset) {
  return static_cast<uint32_t>(g_prefix[offset]) |
         (static_cast<uint32_t>(g_prefix[offset + 1u]) << 8) |
         (static_cast<uint32_t>(g_prefix[offset + 2u]) << 16) |
         (static_cast<uint32_t>(g_prefix[offset + 3u]) << 24);
}

bool range_fits(uint32_t offset, uint32_t size, uint32_t limit) {
  return offset <= limit && size <= limit - offset;
}

bool required_metadata_loaded(uint32_t offset, uint32_t size, Error *error) {
  if (!range_fits(offset, size, g_metadata_prefix_loaded))
    return fail(error, "required ORF metadata exceeds the streaming window");
  return true;
}

bool read_exact(InputStream *input, uint8_t *destination, uint32_t size,
                Error *error) {
  while (size != 0) {
    const long result = input->read(input->context, destination, size, error);
    if (result < 0) {
      if (error != nullptr && error->text[0] != '\0') return false;
      return fail(error, "ORF input read failed");
    }
    if (result == 0) return fail(error, "ORF input ended early");
    const unsigned long amount = static_cast<unsigned long>(result);
    if (amount > size) return fail(error, "ORF input over-read request");
    destination += static_cast<uint32_t>(amount);
    size -= static_cast<uint32_t>(amount);
  }
  return true;
}

bool ensure_prefix(InputStream *input, uint32_t *prefix_size,
                   uint32_t wanted, Error *error) {
  if (wanted > kMetadataPrefixCapacity || wanted > input->total_size)
    return fail(error, "ORF metadata prefix is too large");
  if (*prefix_size >= wanted) return true;
  const uint32_t amount = wanted - *prefix_size;
  if (!read_exact(input, g_prefix + *prefix_size, amount, error)) return false;
  *prefix_size = wanted;
  g_metadata_prefix_loaded = wanted;
  return true;
}

bool skip_exact(InputStream *input, uint32_t size, Error *error) {
  while (size != 0u) {
    uint32_t amount = size;
    if (amount > kMetadataPrefixCapacity)
      amount = kMetadataPrefixCapacity;
    if (!read_exact(input, g_prefix, amount, error)) return false;
    size -= amount;
  }
  return true;
}

uint32_t type_width(uint16_t type) {
  switch (type) {
    case 1:
    case 2:
    case 6:
    case 7:
      return 1;
    case 3:
    case 8:
      return 2;
    case 4:
    case 9:
    case 11:
    case 13:
      return 4;
    case 5:
    case 10:
    case 12:
      return 8;
    default:
      return 0;
  }
}

Entry get_entry(const Ifd &ifd, uint16_t index) {
  const uint32_t offset = ifd.offset + 2u + 12u * index;
  Entry entry;
  entry.offset = offset;
  entry.base = ifd.base;
  entry.limit = ifd.limit;
  entry.tag = load_u16(offset);
  entry.type = load_u16(offset + 2u);
  entry.count = load_u32(offset + 4u);
  return entry;
}

bool entry_data(const Entry &entry, uint32_t *offset, uint32_t *size,
                Error *error) {
  const uint32_t width = type_width(entry.type);
  if (width == 0 || entry.count == 0 || entry.count > UINT32_MAX / width)
    return fail(error, "ORF directory entry has invalid type or count");
  const uint32_t bytes = entry.count * width;
  uint32_t data_offset;
  if (bytes <= 4u) {
    data_offset = entry.offset + 8u;
  } else {
    const uint32_t relative = load_u32(entry.offset + 8u);
    if (relative > UINT32_MAX - entry.base)
      return fail(error, "ORF directory value offset overflowed");
    data_offset = entry.base + relative;
  }
  if (!range_fits(data_offset, bytes, entry.limit))
    return fail(error, "ORF directory value is outside metadata");
  *offset = data_offset;
  *size = bytes;
  return true;
}

bool open_ifd(uint32_t offset, uint32_t base, uint32_t limit,
              uint16_t expected_count, Ifd *ifd, Error *error) {
  if (!range_fits(offset, 2u, limit))
    return fail(error, "ORF directory header is outside metadata");
  if (!required_metadata_loaded(offset, 2u, error)) return false;
  const uint16_t count = load_u16(offset);
  if (count != expected_count)
    return fail(error, "ORF directory does not match regular OM-1 schema");
  const uint32_t body_size = 2u + 12u * count + 4u;
  if (!range_fits(offset, body_size, limit))
    return fail(error, "ORF directory is truncated");
  if (!required_metadata_loaded(offset, body_size, error)) return false;
  ifd->offset = offset;
  ifd->base = base;
  ifd->limit = limit;
  ifd->count = count;

  uint16_t previous = 0;
  for (uint16_t i = 0; i < count; ++i) {
    const Entry entry = get_entry(*ifd, i);
    if ((i != 0 && entry.tag <= previous) || type_width(entry.type) == 0)
      return fail(error, "ORF directory tags are invalid");
    uint32_t data_offset;
    uint32_t data_size;
    if (!entry_data(entry, &data_offset, &data_size, error)) return false;
    (void)data_offset;
    (void)data_size;
    previous = entry.tag;
  }
  const uint32_t next_offset = offset + 2u + 12u * count;
  if (load_u32(next_offset) != 0)
    return fail(error, "ORF has an unsupported linked directory");
  return true;
}

bool find_entry(const Ifd &ifd, uint16_t tag, Entry *result, Error *error) {
  for (uint16_t i = 0; i < ifd.count; ++i) {
    const Entry entry = get_entry(ifd, i);
    if (entry.tag == tag) {
      *result = entry;
      return true;
    }
    if (entry.tag > tag) break;
  }
  return fail(error, "ORF is missing a required metadata tag");
}

bool require_shape(const Entry &entry, uint16_t type, uint32_t count,
                   Error *error) {
  if (entry.type != type || entry.count != count)
    return fail(error, "ORF metadata tag has the wrong type or count");
  return true;
}

bool entry_u16(const Entry &entry, uint32_t index, uint16_t *value,
               Error *error) {
  if (entry.type != kTiffShort || index >= entry.count)
    return fail(error, "ORF SHORT metadata tag is invalid");
  uint32_t offset;
  uint32_t size;
  if (!entry_data(entry, &offset, &size, error)) return false;
  (void)size;
  if (!required_metadata_loaded(offset + index * 2u, 2u, error))
    return false;
  *value = load_u16(offset + index * 2u);
  return true;
}

bool entry_u32(const Entry &entry, uint32_t *value, Error *error) {
  if ((entry.type != kTiffLong && entry.type != kTiffIfd) ||
      entry.count != 1)
    return fail(error, "ORF LONG metadata tag is invalid");
  uint32_t offset;
  uint32_t size;
  if (!entry_data(entry, &offset, &size, error)) return false;
  (void)size;
  if (!required_metadata_loaded(offset, 4u, error)) return false;
  *value = load_u32(offset);
  return true;
}

bool validate_ifd0_schema(const Ifd &ifd, Error *error) {
  if (ifd.count != sizeof(kIfd0Schema) / sizeof(kIfd0Schema[0]))
    return fail(error, "IFD0 does not match regular OM-1 schema");
  for (uint16_t i = 0; i < ifd.count; ++i) {
    const Entry entry = get_entry(ifd, i);
    const SchemaEntry &schema = kIfd0Schema[i];
    if (entry.tag != schema.tag || entry.type != schema.type ||
        entry.count != schema.count)
      return fail(error, "IFD0 does not match regular OM-1 schema");
  }
  return true;
}

bool parse_ifd0(const Ifd &ifd, RawInfo *info, uint32_t *exif_offset,
                Error *error) {
  Entry entry;
  if (!find_entry(ifd, 0x0100, &entry, error) ||
      !entry_u32(entry, &info->width, error) ||
      !find_entry(ifd, 0x0101, &entry, error) ||
      !entry_u32(entry, &info->height, error) ||
      !find_entry(ifd, 0x0102, &entry, error) ||
      !entry_u16(entry, 0, &info->bits_per_sample, error))
    return false;

  uint16_t compression;
  uint16_t photometric;
  uint16_t samples_per_pixel;
  uint32_t rows_per_strip;
  if (!find_entry(ifd, 0x0103, &entry, error) ||
      !entry_u16(entry, 0, &compression, error) ||
      !find_entry(ifd, 0x0106, &entry, error) ||
      !entry_u16(entry, 0, &photometric, error) ||
      !find_entry(ifd, 0x0111, &entry, error) ||
      !entry_u32(entry, &info->strip_offset, error) ||
      !find_entry(ifd, 0x0115, &entry, error) ||
      !entry_u16(entry, 0, &samples_per_pixel, error) ||
      !find_entry(ifd, 0x0116, &entry, error) ||
      !entry_u32(entry, &rows_per_strip, error) ||
      !find_entry(ifd, 0x0117, &entry, error) ||
      !entry_u32(entry, &info->strip_bytes, error) ||
      !find_entry(ifd, 0x8769, &entry, error) ||
      !entry_u32(entry, exif_offset, error))
    return false;

  (void)photometric;
  if (info->width == 0 || info->height == 0 ||
      info->width > kMaxRawWidth || info->height > kMaxRawHeight ||
      (info->width & 1u) != 0 || (info->height & 1u) != 0)
    return fail(error, "ORF raw dimensions are unsupported");
  if (info->bits_per_sample < 12 || info->bits_per_sample > 16 ||
      compression != 1 || samples_per_pixel != 1 ||
      rows_per_strip != info->height)
    return fail(error, "ORF raw IFD is not a regular single strip");
  return true;
}

bool parse_exif(const Ifd &exif, uint32_t *maker_base,
                uint32_t *maker_size, RawInfo *info, Error *error) {
  Entry entry;
  if (!find_entry(exif, 0x927c, &entry, error) ||
      entry.type != kTiffUndefined || entry.count < 16u)
    return fail(error, "ORF MakerNote tag is invalid");
  uint32_t data_size;
  if (!entry_data(entry, maker_base, &data_size, error)) return false;
  *maker_size = entry.count;

  if (!find_entry(exif, 0xa302, &entry, error) ||
      !require_shape(entry, kTiffUndefined, 8, error))
    return false;
  uint32_t cfa_offset;
  if (!entry_data(entry, &cfa_offset, &data_size, error)) return false;
  if (!required_metadata_loaded(cfa_offset, 8u, error)) return false;
  if (load_u16(cfa_offset) != 2 || load_u16(cfa_offset + 2u) != 2)
    return fail(error, "ORF CFA repeat dimensions are not 2 by 2");
  uint32_t color_counts[3] = {};
  for (uint32_t i = 0; i < 4; ++i) {
    const uint8_t color = g_prefix[cfa_offset + 4u + i];
    if (color > 2) return fail(error, "ORF CFA color value is invalid");
    info->cfa[i] = color;
    ++color_counts[color];
  }
  if (color_counts[0] != 1 || color_counts[1] != 2 ||
      color_counts[2] != 1)
    return fail(error, "ORF CFA is not a Bayer mosaic");
  return true;
}

bool verify_maker_header(uint32_t maker_base, uint32_t maker_size,
                         Error *error) {
  static constexpr uint8_t kHeader[16] = {
      0x4f, 0x4d, 0x20, 0x53, 0x59, 0x53, 0x54, 0x45,
      0x4d, 0x00, 0x00, 0x00, 0x49, 0x49, 0x04, 0x00,
  };
  if (maker_size < sizeof(kHeader))
    return fail(error, "ORF MakerNote is truncated");
  if (!required_metadata_loaded(maker_base, sizeof(kHeader), error))
    return false;
  for (uint32_t i = 0; i < sizeof(kHeader); ++i) {
    if (g_prefix[maker_base + i] != kHeader[i])
      return fail(error, "ORF MakerNote header is invalid");
  }
  return true;
}

bool maker_sub_ifd(const Ifd &root, uint16_t tag, uint16_t expected_count,
                   uint32_t maker_base, uint32_t maker_limit, Ifd *sub_ifd,
                   Error *error) {
  Entry entry;
  uint32_t relative;
  if (!find_entry(root, tag, &entry, error) ||
      !require_shape(entry, kTiffIfd, 1, error) ||
      !entry_u32(entry, &relative, error))
    return false;
  if (relative > UINT32_MAX - maker_base)
    return fail(error, "ORF MakerNote IFD offset overflowed");
  return open_ifd(maker_base + relative, maker_base, maker_limit,
                  expected_count, sub_ifd, error);
}

bool verify_camera_type(const Ifd &equipment, Error *error) {
  Entry entry;
  if (!find_entry(equipment, 0x0100, &entry, error) ||
      !require_shape(entry, kTiffAscii, 6, error))
    return false;
  uint32_t offset;
  uint32_t size;
  if (!entry_data(entry, &offset, &size, error)) return false;
  static constexpr uint8_t kCameraType[6] = {'S', '0', '0', '9', '5', 0};
  if (!required_metadata_loaded(offset, sizeof(kCameraType), error))
    return false;
  for (uint32_t i = 0; i < sizeof(kCameraType); ++i) {
    if (g_prefix[offset + i] != kCameraType[i])
      return fail(error, "ORF CameraType2 is not OM-1");
  }
  return true;
}

bool parse_image_processing(const Ifd &processing, RawInfo *info,
                            Error *error) {
  Entry entry;
  if (!find_entry(processing, 0x0600, &entry, error) ||
      !require_shape(entry, kTiffShort, 4, error))
    return false;
  for (uint32_t i = 0; i < 4; ++i) {
    if (!entry_u16(entry, i, &info->black[i], error)) return false;
  }

  if (!find_entry(processing, 0x0611, &entry, error) ||
      !require_shape(entry, kTiffShort, 2, error) ||
      !entry_u16(entry, 0, &info->valid_bits, error) ||
      !find_entry(processing, 0x0612, &entry, error) ||
      !require_shape(entry, kTiffShort, 2, error))
    return false;
  // crop_x/crop_y are 32-bit public fields, while these tags are SHORT[2].
  uint16_t crop_coordinate;
  if (!entry_u16(entry, 0, &crop_coordinate, error)) return false;
  info->crop_x = crop_coordinate;
  if (!find_entry(processing, 0x0613, &entry, error) ||
      !require_shape(entry, kTiffShort, 2, error) ||
      !entry_u16(entry, 0, &crop_coordinate, error))
    return false;
  info->crop_y = crop_coordinate;
  if (!find_entry(processing, 0x0614, &entry, error) ||
      !require_shape(entry, kTiffLong, 1, error) ||
      !entry_u32(entry, &info->crop_width, error) ||
      !find_entry(processing, 0x0615, &entry, error) ||
      !require_shape(entry, kTiffLong, 1, error) ||
      !entry_u32(entry, &info->crop_height, error))
    return false;

  // Olympus numbers these controls as decimal-looking groups: 0640..0649,
  // then 0650..0653.  RawInfo retains tag-minus-0x0640 indexing, so its six
  // 0x064a..0x064f slots intentionally stay zero.
  for (uint32_t i = 0; i < 20; ++i) {
    if (i >= 10 && i < 16) continue;
    if (!find_entry(processing, static_cast<uint16_t>(0x0640u + i),
                    &entry, error) ||
        !require_shape(entry, kTiffShort, 1, error) ||
        !entry_u16(entry, 0, &info->codec[i], error))
      return false;
  }

  if (info->valid_bits != 12 || info->valid_bits > info->bits_per_sample ||
      info->crop_x > info->width || info->crop_y > info->height ||
      info->crop_width == 0 || info->crop_height == 0 ||
      info->crop_width > info->width - info->crop_x ||
      info->crop_height > info->height - info->crop_y)
    return fail(error, "ORF precision or active crop is not regular OM-1");
  const uint32_t white = (1u << info->valid_bits) - 1u;
  for (uint32_t i = 0; i < 4; ++i) {
    if (info->black[i] > white)
      return fail(error, "ORF black level exceeds valid precision");
  }
  return true;
}

bool validate_codec(const RawInfo &info, Error *error) {
  const uint16_t *c = info.codec;
  if (c[0] != 0 || c[1] != 5 || c[2] != 0 || c[3] != 2 ||
      c[4] != 5 || c[5] != 4 || c[6] != 3 || c[7] != 2 ||
      c[8] != 3 || c[9] != 4 || c[16] != 7 || c[17] != 15 ||
      c[18] != 12 || c[19] != 5)
    return fail(error, "ORF predictive codec is not the regular OM-1 profile");
  return true;
}

bool parse_metadata(uint32_t prefix_size, RawInfo *info, Error *error) {
  Ifd ifd0;
  if (!open_ifd(8, 0, prefix_size,
                static_cast<uint16_t>(sizeof(kIfd0Schema) /
                                      sizeof(kIfd0Schema[0])),
                &ifd0, error) ||
      !validate_ifd0_schema(ifd0, error))
    return false;

  uint32_t exif_offset;
  if (!parse_ifd0(ifd0, info, &exif_offset, error)) return false;
  Ifd exif;
  if (!open_ifd(exif_offset, 0, prefix_size, 39, &exif, error)) return false;

  uint32_t maker_base = 0;
  uint32_t maker_size = 0;
  if (!parse_exif(exif, &maker_base, &maker_size, info, error) ||
      !verify_maker_header(maker_base, maker_size, error))
    return false;
  if (maker_base > prefix_size || maker_size > prefix_size - maker_base)
    return fail(error, "ORF MakerNote is outside metadata");
  const uint32_t maker_limit = maker_base + maker_size;

  Ifd root;
  if (!open_ifd(maker_base + 16u, maker_base, maker_limit, 8, &root, error))
    return false;
  Ifd equipment;
  if (!maker_sub_ifd(root, 0x2010, 26, maker_base, maker_limit, &equipment,
                     error) ||
      !verify_camera_type(equipment, error))
    return false;
  Ifd processing;
  if (!maker_sub_ifd(root, 0x2040, 201, maker_base, maker_limit, &processing,
                     error) ||
      !parse_image_processing(processing, info, error) ||
      !validate_codec(*info, error))
    return false;
  return true;
}

struct PayloadReader {
  InputStream *input;
  uint32_t source_remaining;
  const uint8_t *cursor;
  const uint8_t *end;
  uint32_t bytes_read;
  uint32_t bit_offset;

  __attribute__((noinline, cold)) bool refill(Error *error) {
    if (source_remaining == 0)
      return fail(error, "ORF compressed strip ended early");
    uint32_t wanted = source_remaining;
    if (wanted > kPayloadReadChunk) wanted = kPayloadReadChunk;
    const long result = input->read(input->context, g_prefix, wanted, error);
    if (result < 0) {
      if (error != nullptr && error->text[0] != '\0') return false;
      return fail(error, "ORF strip read failed");
    }
    if (result == 0) return fail(error, "ORF compressed strip ended early");
    const unsigned long amount = static_cast<unsigned long>(result);
    if (amount > wanted) return fail(error, "ORF input over-read request");
    cursor = g_prefix;
    end = g_prefix + static_cast<uint32_t>(amount);
    source_remaining -= static_cast<uint32_t>(amount);
    bytes_read += static_cast<uint32_t>(amount);
    return true;
  }

  __attribute__((always_inline)) inline bool byte(uint8_t *value,
                                                   Error *error) {
    if (__builtin_expect(cursor == end, 0) && !refill(error))
      return false;
    *value = *cursor++;
    return true;
  }

  __attribute__((noinline, cold)) bool prepare_window(
      uint32_t minimum_bytes, uint32_t *available_bits, Error *error) {
    uint32_t remaining = static_cast<uint32_t>(end - cursor);
    for (uint32_t i = 0; i < remaining; ++i) g_prefix[i] = cursor[i];
    cursor = g_prefix;
    end = g_prefix + remaining;

    while (remaining < minimum_bytes && source_remaining != 0u) {
      uint32_t wanted = kPayloadReadChunk - remaining;
      if (wanted > source_remaining) wanted = source_remaining;
      const long result =
          input->read(input->context, g_prefix + remaining, wanted, error);
      if (result < 0) {
        if (error != nullptr && error->text[0] != '\0') return false;
        return fail(error, "ORF strip read failed");
      }
      if (result == 0)
        return fail(error, "ORF compressed strip ended early");
      const unsigned long amount = static_cast<unsigned long>(result);
      if (amount > wanted) return fail(error, "ORF input over-read request");
      remaining += static_cast<uint32_t>(amount);
      end = g_prefix + remaining;
      source_remaining -= static_cast<uint32_t>(amount);
      bytes_read += static_cast<uint32_t>(amount);
    }

    if (remaining >= minimum_bytes) {
      *available_bits = UINT32_MAX;
      return true;
    }
    const uint32_t actual_bits = remaining * 8u - bit_offset;
    for (uint32_t i = remaining; i < minimum_bytes; ++i) g_prefix[i] = 0;
    *available_bits = actual_bits;
    return true;
  }

  __attribute__((always_inline)) inline bool codeword_window(
      uint32_t *window, uint32_t *available_bits, Error *error) {
    *available_bits = UINT32_MAX;
    if (__builtin_expect(static_cast<uint32_t>(end - cursor) < 5u, 0) &&
        !prepare_window(5u, available_bits, error))
      return false;

    const uint32_t first = __builtin_bswap32(
        reinterpret_cast<const PackedU32 *>(cursor)->value);
    const uint32_t offset = bit_offset;
    *window = (first << offset) |
              (static_cast<uint32_t>(cursor[4]) >> (8u - offset));
    return true;
  }

  // Two codec widths are known before either codeword is consumed.  Load one
  // 72-bit lookahead so the second window can be derived from the first and
  // the following 32 bits, avoiding an overlapping reload and a second
  // cursor update on the serial VLC dependency chain.
  __attribute__((always_inline)) inline bool codeword_pair_windows(
      uint32_t *first_window, uint32_t *following_window,
      uint32_t *available_bits, Error *error) {
    *available_bits = UINT32_MAX;
    if (__builtin_expect(static_cast<uint32_t>(end - cursor) < 9u, 0) &&
        !prepare_window(9u, available_bits, error))
      return false;

    const uint32_t first = __builtin_bswap32(
        reinterpret_cast<const PackedU32 *>(cursor)->value);
    const uint32_t following = __builtin_bswap32(
        reinterpret_cast<const PackedU32 *>(cursor + 4u)->value);
    const uint32_t offset = bit_offset;
    uint32_t aligned_first = first << offset;
#if defined(__arm__)
    // ARM register shifts define LSR #32 as zero, which removes an otherwise
    // per-pair offset==0 branch while retaining the portable path below for
    // host validation builds.
    const uint32_t inverse = 32u - offset;
    asm("orr %0, %0, %1, lsr %2"
        : "+r"(aligned_first)
        : "r"(following), "r"(inverse));
#else
    if (offset != 0u) aligned_first |= following >> (32u - offset);
#endif
    *first_window = aligned_first;
    *following_window =
        (following << offset) |
        (static_cast<uint32_t>(cursor[8]) >> (8u - offset));
    return true;
  }

  __attribute__((always_inline)) inline bool consume_codeword(
      uint32_t count, uint32_t available_bits, Error *error) {
    if (__builtin_expect(count > available_bits, 0))
      return fail(error, "ORF compressed strip ended early");
    const uint32_t position = bit_offset + count;
    cursor += position >> 3;
    bit_offset = position & 7u;
    return true;
  }

  bool drain(Error *error) {
    cursor = end;
    while (source_remaining != 0) {
      if (!refill(error)) return false;
      cursor = end;
    }
    return true;
  }
};

// A normal codeword is 3 prefix bits, up to 12 unary bits, and <=15 remainder
// bits.  The escape form is exactly 31 bits: 3 + 12 + (15-w) + 1 + w.
// Consequently one aligned 32-bit lookahead decodes every sensel without a
// reservoir or repeated bit-pump calls in the serial dependency chain.
__attribute__((always_inline)) inline uint32_t codeword_position(
    uint32_t window, uint32_t wbits) {
  const uint32_t zeros =
      static_cast<uint32_t>(__builtin_clz((window << 3) | 1u));
  return zeros >= 12u ? 31u - wbits : 4u + zeros;
}

__attribute__((always_inline)) inline void decode_window_scalar(
    uint32_t window, uint32_t wbits, uint32_t position,
    int32_t *magnitude, uint32_t *refine, int32_t *residual) {
  const uint32_t zeros =
      static_cast<uint32_t>(__builtin_clz((window << 3) | 1u));
  uint32_t quotient = zeros;
  if (zeros >= 12u) {
    const uint32_t quotient_bits = 15u - wbits;
    quotient = quotient_bits == 0u
                   ? 0u
                   : (window << 15) >> (32u - quotient_bits);
  }
  const uint32_t remainder = (window << position) >> (32u - wbits);
  const uint32_t unsigned_code = remainder | (quotient << wbits);
  *magnitude = static_cast<int32_t>(unsigned_code);
  *refine = (window >> 29) & 3u;
  *residual = (window >> 31) == 0u
                  ? static_cast<int32_t>(unsigned_code)
                  : ~static_cast<int32_t>(unsigned_code);
}

#if defined(__ARM_NEON) && !defined(OM1_DISABLE_NEON_BITSTREAM)
struct NeonCodePair {
  int32x2_t magnitude;
  uint32x2_t refine;
  int32x2_t residual;
};

NeonCodePair g_escape_pair;

__attribute__((noinline, cold)) void decode_neon_escape_pair(
    uint32_t window0, uint32_t window1,
    uint32_t wbits0, uint32_t wbits1, uint32_t position0,
    uint32_t position1) {
  int32_t magnitude0;
  int32_t magnitude1;
  uint32_t refine0;
  uint32_t refine1;
  int32_t residual0;
  int32_t residual1;
  decode_window_scalar(window0, wbits0, position0, &magnitude0, &refine0,
                       &residual0);
  decode_window_scalar(window1, wbits1, position1, &magnitude1, &refine1,
                       &residual1);
  g_escape_pair.magnitude = vset_lane_s32(
      magnitude1, vdup_n_s32(magnitude0), 1);
  g_escape_pair.refine = vset_lane_u32(refine1, vdup_n_u32(refine0), 1);
  g_escape_pair.residual = vset_lane_s32(
      residual1, vdup_n_s32(residual0), 1);
}

// Boundary refills are rare.  Publishing their result through cold storage
// keeps the normal pair result in NEON registers instead of forcing a stack
// representation merely because the slow helper is out of line.
__attribute__((noinline, cold)) bool decode_neon_code_pair_boundary(
    PayloadReader *reader, uint32x2_t widths,
    int32x2_t negative_thirty_two, Error *error) {
  const uint32_t wbits0 = vget_lane_u32(widths, 0);
  uint32_t window0;
  uint32_t following_window;
  uint32_t available_bits;
  if (!reader->codeword_pair_windows(&window0, &following_window,
                                     &available_bits, error))
    return false;
  const uint32_t zeros0 =
      static_cast<uint32_t>(__builtin_clz((window0 << 3) | 1u));
  const uint32_t position0 =
      zeros0 >= 12u ? 31u - wbits0 : 4u + zeros0;
  const uint32_t consumed0 = position0 + wbits0;
  const uint32_t window1 =
      (window0 << consumed0) |
      (following_window >> (32u - consumed0));

  const uint32_t zeros1 =
      static_cast<uint32_t>(__builtin_clz((window1 << 3) | 1u));
  uint32_t wbits1;
  asm volatile("vmov.32 %0, %P1[1]"
               : "=r"(wbits1)
               : "w"(widths)
               : "memory");
  const uint32_t position1 =
      zeros1 >= 12u ? 31u - wbits1 : 4u + zeros1;
  if (zeros0 >= 12u || zeros1 >= 12u) {
    decode_neon_escape_pair(window0, window1, wbits0, wbits1,
                            position0, position1);
    return reader->consume_codeword(consumed0 + position1 + wbits1,
                                    available_bits, error);
  }

  uint32x2_t windows;
  asm volatile("vmov %P0, %1, %2"
               : "=w"(windows)
               : "r"(window0), "r"(window1));
  uint32x2_t quotients;
  asm volatile("vmov %P0, %1, %2"
               : "=w"(quotients)
               : "r"(zeros0), "r"(zeros1));
  const uint32x2_t shifted =
      vshl_u32(vshl_n_u32(windows, 4),
               vreinterpret_s32_u32(quotients));
  const int32x2_t remainder_right =
      vadd_s32(vreinterpret_s32_u32(widths), negative_thirty_two);
  const uint32x2_t remainders = vshl_u32(shifted, remainder_right);
  const uint32x2_t magnitudes =
      vorr_u32(remainders,
               vshl_u32(quotients, vreinterpret_s32_u32(widths)));
  const uint32x2_t refines =
      vshr_n_u32(vshl_n_u32(windows, 1), 30);
  const int32x2_t sign_masks =
      vshr_n_s32(vreinterpret_s32_u32(windows), 31);

  g_escape_pair.magnitude = vreinterpret_s32_u32(magnitudes);
  g_escape_pair.refine = refines;
  g_escape_pair.residual =
      veor_s32(vreinterpret_s32_u32(magnitudes), sign_masks);
  return reader->consume_codeword(consumed0 + position1 + wbits1,
                                  available_bits, error);
}

// Pairing is possible even though the stream is variable-length: at columns
// c,c+1 both Rice widths depend on magnitudes c-2,c-1, which are already
// known.  Scalar CLZ stages codeword 1's address; NEON then performs CLZ,
// per-lane variable extraction, quotient/remainder assembly, refinement, and
// sign mapping on both actual VLC codewords.
__attribute__((always_inline)) inline bool decode_neon_code_pair(
    const uint8_t *&cursor, uint32_t &bit_offset, uint32x2_t widths,
    int32x2_t negative_thirty_two, NeonCodePair *decoded) {
  const uint32_t wbits0 = vget_lane_u32(widths, 0);
  uint32_t window0;
  uint32_t following_window;
  const uint32_t first = __builtin_bswap32(
      reinterpret_cast<const PackedU32 *>(cursor)->value);
  const uint32_t following = __builtin_bswap32(
      reinterpret_cast<const PackedU32 *>(cursor + 4u)->value);
  uint32_t aligned_first = first << bit_offset;
#if defined(__arm__)
  const uint32_t inverse = 32u - bit_offset;
  asm("orr %0, %0, %1, lsr %2"
      : "+r"(aligned_first)
      : "r"(following), "r"(inverse));
#else
  if (bit_offset != 0u)
    aligned_first |= following >> (32u - bit_offset);
#endif
  window0 = aligned_first;
  following_window =
      (following << bit_offset) |
      (static_cast<uint32_t>(cursor[8]) >> (8u - bit_offset));
  const uint32_t zeros0 =
      static_cast<uint32_t>(__builtin_clz((window0 << 3) | 1u));
  const uint32_t consumed0 =
      __builtin_expect(zeros0 >= 12u, 0) ? 31u
                                         : 4u + zeros0 + wbits0;
  const uint32_t window1 =
      (window0 << consumed0) |
      (following_window >> (32u - consumed0));

  const uint32_t zeros1 =
      static_cast<uint32_t>(__builtin_clz((window1 << 3) | 1u));
  uint32_t wbits1;
  asm volatile("vmov.32 %0, %P1[1]"
               : "=r"(wbits1)
               : "w"(widths)
               : "memory");
  if (__builtin_expect(zeros0 >= 12u || zeros1 >= 12u, 0)) {
    const uint32_t position0 =
        zeros0 >= 12u ? 31u - wbits0 : 4u + zeros0;
    const uint32_t position1 =
        zeros1 >= 12u ? 31u - wbits1 : 4u + zeros1;
    decode_neon_escape_pair(window0, window1, wbits0, wbits1,
                            position0, position1);
    *decoded = g_escape_pair;
    const uint32_t count = consumed0 + position1 + wbits1;
    const uint32_t next = bit_offset + count;
    cursor += next >> 3;
    bit_offset = next & 7u;
    return true;
  }

  uint32x2_t windows;
  asm volatile("vmov %P0, %1, %2"
               : "=w"(windows)
               : "r"(window0), "r"(window1));
  uint32x2_t quotients;
  asm volatile("vmov %P0, %1, %2"
               : "=w"(quotients)
               : "r"(zeros0), "r"(zeros1));
  const uint32x2_t shifted =
      vshl_u32(vshl_n_u32(windows, 4),
               vreinterpret_s32_u32(quotients));
  const int32x2_t remainder_right =
      vadd_s32(vreinterpret_s32_u32(widths), negative_thirty_two);
  const uint32x2_t remainders = vshl_u32(shifted, remainder_right);
  const uint32x2_t magnitudes =
      vorr_u32(remainders,
               vshl_u32(quotients, vreinterpret_s32_u32(widths)));
  const uint32x2_t refines =
      vshr_n_u32(vshl_n_u32(windows, 1), 30);
  const int32x2_t sign_masks =
      vshr_n_s32(vreinterpret_s32_u32(windows), 31);
  const int32x2_t residuals =
      veor_s32(vreinterpret_s32_u32(magnitudes), sign_masks);

  decoded->magnitude = vreinterpret_s32_u32(magnitudes);
  decoded->refine = refines;
  decoded->residual = residuals;
  const uint32_t count = consumed0 + 4u + zeros1 + wbits1;
  const uint32_t next = bit_offset + count;
  cursor += next >> 3;
  bit_offset = next & 7u;
  return true;
}
#endif

// Kept as the scalar A/B path while the paired NEON loop is selected below.
__attribute__((always_inline)) inline bool decode_standard_code(
    PayloadReader *reader, uint32_t wbits, int32_t *magnitude,
    uint32_t *refine, int32_t *residual, Error *error) {
  uint32_t window;
  uint32_t available_bits;
  if (!reader->codeword_window(&window, &available_bits, error)) return false;
  const uint32_t position = codeword_position(window, wbits);
  decode_window_scalar(window, wbits, position, magnitude, refine, residual);
  return reader->consume_codeword(position + wbits, available_bits, error);
}

struct ScalarCodecState {
  int32_t context[4];
  int32_t last_predictor;
  int32_t delayed_refinement;
  int32_t magnitude;
  int32_t delayed_magnitude;
};

__attribute__((always_inline)) inline uint32_t scalar_code_width(
    int32_t previous_magnitude, int32_t selector) {
  const uint32_t value =
      static_cast<uint32_t>(previous_magnitude) & 0xffffu;
  const int32_t length =
      value == 0 ? 0 : 32 - static_cast<int32_t>(__builtin_clz(value));
  const bool selected = selector >= 3;
  const int32_t threshold1 = selected ? 3 : 7;
  const int32_t threshold2 = selected ? 2 : 4;
  int32_t adjustment = length - threshold1;
  if (adjustment < -1) adjustment = -1;
  int32_t width = threshold2 + adjustment + 1;
  if (width > 15) width = 15;
  return static_cast<uint32_t>(width);
}

#if defined(OM1_PARALLEL_CODEC)
void copy_error_value(Error *destination, const Error &source) {
  if (destination == nullptr) return;
  for (uint32_t i = 0; i < sizeof(destination->text); ++i)
    destination->text[i] = source.text[i];
}

bool notify_event(uint32_t *event, Error *error) {
  (void)__atomic_add_fetch(event, 1u, __ATOMIC_RELEASE);
  return wake_private_word(event, error);
}

// Queue events are monotonic predicates, not counting semaphores.  Register a
// waiter before its final predicate recheck and exchange that registration in
// the publisher.  This preserves the futex lost-wake proof while avoiding a
// kernel FUTEX_WAKE syscall for the overwhelmingly common case in which the
// other codec stage is already running.
bool notify_registered_waiter(uint32_t *event, uint32_t *waiting,
                              Error *error) {
  (void)__atomic_add_fetch(event, 1u, __ATOMIC_RELEASE);
  if (__atomic_exchange_n(waiting, 0u, __ATOMIC_ACQ_REL) == 0u) return true;
  return wake_private_word(event, error);
}

// This runs once per row, not per pixel.  Keeping its futex/error paths out of
// the producer function also prevents their live state from spilling the
// serial VLC loop's column and window registers.
__attribute__((noinline)) bool acquire_residual_row(
    ParallelCodecState *pipeline, ResidualRowSlot **slot,
    uint32_t *sequence, Error *error) {
  for (;;) {
    if (__atomic_load_n(&pipeline->worker_failed, __ATOMIC_ACQUIRE) != 0u) {
      copy_error_value(error, pipeline->worker_error);
      return false;
    }
    const uint32_t produced =
        __atomic_load_n(&pipeline->produced, __ATOMIC_RELAXED);
    const uint32_t consumed =
        __atomic_load_n(&pipeline->consumed, __ATOMIC_ACQUIRE);
    if (produced - consumed != kResidualRingRows) {
      *slot = &pipeline->rows[produced % kResidualRingRows];
      *sequence = produced;
      return true;
    }

    const uint32_t event =
        __atomic_load_n(&pipeline->space_event, __ATOMIC_ACQUIRE);
    __atomic_store_n(&pipeline->space_waiting, 1u, __ATOMIC_RELEASE);
    if (__atomic_load_n(&pipeline->worker_failed, __ATOMIC_ACQUIRE) != 0u) {
      __atomic_store_n(&pipeline->space_waiting, 0u, __ATOMIC_RELEASE);
      continue;
    }
    if (__atomic_load_n(&g_codec_thread.clear_tid, __ATOMIC_ACQUIRE) == 0u) {
      __atomic_store_n(&pipeline->space_waiting, 0u, __ATOMIC_RELEASE);
      return fail(error, "ORF codec worker exited unexpectedly");
    }
    if (__atomic_load_n(&pipeline->produced, __ATOMIC_RELAXED) -
            __atomic_load_n(&pipeline->consumed, __ATOMIC_ACQUIRE) !=
        kResidualRingRows) {
      __atomic_store_n(&pipeline->space_waiting, 0u, __ATOMIC_RELEASE);
      continue;
    }
    const bool waited = wait_private_word(&pipeline->space_event, event, error);
    __atomic_store_n(&pipeline->space_waiting, 0u, __ATOMIC_RELEASE);
    if (!waited) return false;
  }
}

bool publish_residual_row(ParallelCodecState *pipeline, uint32_t sequence,
                          Error *error) {
  __atomic_store_n(&pipeline->produced, sequence + 1u, __ATOMIC_RELEASE);
  return notify_registered_waiter(&pipeline->data_event,
                                  &pipeline->data_waiting, error);
}

__attribute__((always_inline)) inline bool decode_scalar_residual(
    PayloadReader *reader, uint32_t column, ScalarCodecState *state,
    int32_t *output, Error *error) {
  const int32_t previous_magnitude = state->delayed_magnitude;
  state->delayed_magnitude = state->magnitude;
  const int32_t selector =
      (column > 1u && previous_magnitude <= 16)
          ? state->context[3] + 1
          : 0;
  state->context[3] = state->context[2];
  state->context[2] = selector;
  const uint32_t width =
      column > 1u ? scalar_code_width(previous_magnitude, selector) : 4u;

  uint32_t refine;
  int32_t residual;
  if (!decode_standard_code(reader, width, &state->magnitude, &refine,
                            &residual, error))
    return false;
  const int32_t history = column < 2u ? 0 : state->delayed_refinement;
  residual = static_cast<int32_t>(refine) + 4 * (history + residual);
  const int32_t refinement_value =
      ((residual >> 1) & ~1) + history + (residual >> 2);
  state->delayed_refinement = state->context[0];
  state->context[0] = refinement_value >> 5;
  *output = residual;
  return true;
}

__attribute__((noinline, hot, optimize("O3"))) bool decode_residual_rows_neon(
    PayloadReader *reader, const RawInfo &info, ParallelCodecState *pipeline,
    Error *error) {
  ScalarCodecState scalar = {};
  const int32x2_t zero = vdup_n_s32(0);
  const int32x2_t one = vdup_n_s32(1);
  const int32x2_t two = vdup_n_s32(2);
  const int32x2_t negative_thirty_two = vdup_n_s32(-32);

  for (uint32_t row = 0; row < info.height; ++row) {
    ResidualRowSlot *slot;
    uint32_t sequence;
    if (!acquire_residual_row(pipeline, &slot, &sequence, error)) return false;
    int32_t *const output = slot->values;
    if (!decode_scalar_residual(reader, 0u, &scalar, output, error) ||
        !decode_scalar_residual(reader, 1u, &scalar, output + 1u, error))
      return false;
    const uint8_t *bit_cursor = reader->cursor;
    // The normal pair needs bytes [cursor, cursor + 8].  Keeping end - 8 as
    // an integer address turns the per-pair `(end - cursor) < 9` into one
    // compare, while retaining the exact logical end for the rare refill.
    unsigned long bit_fast_limit =
        reinterpret_cast<unsigned long>(reader->end) - 8u;
    uint32_t bit_offset = reader->bit_offset;

    uint32x2_t magnitudes = vdup_n_u32(
        static_cast<uint32_t>(scalar.delayed_magnitude));
    magnitudes = vset_lane_u32(
        static_cast<uint32_t>(scalar.magnitude), magnitudes, 1);
    int32x2_t selectors = zero;
    int32x2_t histories = vdup_n_s32(scalar.delayed_refinement);
    histories = vset_lane_s32(scalar.context[0], histories, 1);
    vst1_s32(slot->initial_histories, histories);

    int32_t *output_cursor = output + 2u;
    const int32_t *const output_end = output + info.width;
    for (; output_cursor < output_end; output_cursor += 2u) {
      selectors = vbsl_s32(
          vcle_u32(magnitudes, vdup_n_u32(16u)),
          vadd_s32(selectors, one), zero);
      const uint32x2_t lengths = vsub_u32(
          vdup_n_u32(16u),
          vclz_u32(vshl_n_u32(magnitudes, 16)));
      const uint32x2_t selected =
          vcgt_s32(selectors, two);
      const int32x2_t offset =
          vbic_s32(two, vreinterpret_s32_u32(selected));
      const int32x2_t minimum = vadd_s32(two, offset);
      const uint32x2_t widths = vreinterpret_u32_s32(
          vmin_s32(vmax_s32(vsub_s32(vreinterpret_s32_u32(lengths), offset),
                            minimum),
                   vdup_n_s32(15)));

      NeonCodePair code;
      if (__builtin_expect(
              reinterpret_cast<unsigned long>(bit_cursor) >= bit_fast_limit,
              0)) {
        reader->cursor = bit_cursor;
        reader->end = reinterpret_cast<const uint8_t *>(bit_fast_limit + 8u);
        reader->bit_offset = bit_offset;
        if (!decode_neon_code_pair_boundary(
                reader, widths, negative_thirty_two, error))
          return false;
        bit_cursor = reader->cursor;
        bit_fast_limit =
            reinterpret_cast<unsigned long>(reader->end) - 8u;
        bit_offset = reader->bit_offset;
        code = g_escape_pair;
      } else {
        (void)decode_neon_code_pair(bit_cursor, bit_offset, widths,
                                    negative_thirty_two, &code);
      }
      magnitudes = vreinterpret_u32_s32(code.magnitude);
      // Preserve the two refinement bits below the signed code residual.
      // The worker recovers the code with an arithmetic shift and applies the
      // history recurrence on its otherwise underloaded core.
      const int32x2_t packed = vsli_n_s32(
          vreinterpret_s32_u32(code.refine), code.residual, 2);
      vst1_s32(output_cursor, packed);
    }
    reader->cursor = bit_cursor;
    reader->end = reinterpret_cast<const uint8_t *>(bit_fast_limit + 8u);
    reader->bit_offset = bit_offset;
    if (!publish_residual_row(pipeline, sequence, error)) return false;
  }
  return true;
}
#endif

__attribute__((always_inline)) inline int32_t adjust_predictor(
    int32_t north, int32_t west, int32_t northwest) {
  if (west < north || northwest < west) {
    if (northwest <= north && west >= north) {
      north = west;
    } else if (northwest < north || west >= north) {
      if (northwest > west || west >= north) {
        const int32_t north_difference = north - northwest;
        const uint32_t absolute_north = static_cast<uint32_t>(
            north_difference < 0 ? -north_difference : north_difference);
        if (absolute_north > 32u) {
          north += west - northwest;
        } else {
          const int32_t west_difference = west - northwest;
          const uint32_t absolute_west = static_cast<uint32_t>(
              west_difference < 0 ? -west_difference : west_difference);
          if (absolute_west <= 32u)
            north = (north + west) >> 1;
          else
            north += west - northwest;
        }
      }
    } else {
      north = west;
    }
  }
  return north;
}

__attribute__((always_inline)) inline bool decode_scalar_pixel(
    PayloadReader *reader, uint32_t row, uint32_t column, uint16_t *samples,
    uint16_t northwest_delay[2], ScalarCodecState *state, Error *error) {
  const int32_t previous_magnitude = state->delayed_magnitude;
  state->delayed_magnitude = state->magnitude;
  const int32_t selector =
      (column > 1u && previous_magnitude <= 16)
          ? state->context[3] + 1
          : 0;
  state->context[3] = state->context[2];
  state->context[2] = selector;
  const uint32_t width =
      column > 1u ? scalar_code_width(previous_magnitude, selector) : 4u;

  uint32_t refine;
  int32_t residual;
  if (!decode_standard_code(reader, width, &state->magnitude, &refine,
                            &residual, error))
    return false;

  const int32_t history = column < 2u ? 0 : state->delayed_refinement;
  residual = static_cast<int32_t>(refine) + 4 * (history + residual);
  const int32_t refinement_value =
      ((residual >> 1) & ~1) + history + (residual >> 2);
  state->delayed_refinement = state->context[0];
  state->context[0] = refinement_value >> 5;

  const int32_t west = column < 2u ? 0 : state->context[1];
  const uint16_t old_north = samples[column];
  int32_t north = row < 2u ? 0 : static_cast<int32_t>(old_north);
  const int32_t northwest =
      (row < 2u || column < 2u)
          ? 0
          : static_cast<int32_t>(northwest_delay[column & 1u]);
  northwest_delay[column & 1u] = old_north;
  state->context[1] = state->last_predictor;
  north = adjust_predictor(north, west, northwest);

  const int32_t high = residual + north;
  state->last_predictor = high;
  if (static_cast<uint32_t>(high) > 65535u)
    return fail(error, "ORF decoded sample is outside uint16 range");
  samples[column] = static_cast<uint16_t>(high);
  return true;
}

__attribute__((unused, hot, optimize("O3"))) bool
decode_standard_pixels_scalar(
    PayloadReader *reader, const RawInfo &info, RowConsumer consumer,
    void *consumer_context, Error *error) {
  ScalarCodecState state = {};

  for (uint32_t row = 0; row < info.height; ++row) {
    uint16_t *samples = g_rows[row & 1u];
    uint16_t northwest_delay[2] = {};
    for (uint32_t column = 0; column < info.width; ++column) {
      if (!decode_scalar_pixel(reader, row, column, samples, northwest_delay,
                               &state, error))
        return false;
    }
    if (!consumer(consumer_context, row, samples, info.width, error)) {
      if (error != nullptr && error->text[0] != '\0') return false;
      return fail(error, "ORF row consumer failed");
    }
  }

  return true;
}

#if defined(__ARM_NEON) && !defined(OM1_DISABLE_NEON_BITSTREAM)
__attribute__((always_inline)) inline int32x2_t predict_neon_pair(
    int32x2_t north, int32x2_t west, int32x2_t northwest) {
  const uint32x2_t west_below_north = vclt_s32(west, north);
  const uint32x2_t choose_west =
      vbsl_u32(west_below_north, vcge_s32(northwest, north),
               vcle_s32(northwest, north));
  const int32x2_t lower = vmin_s32(north, west);
  const int32x2_t upper = vmax_s32(north, west);
  const uint32x2_t calculate =
      vand_u32(vcgt_s32(northwest, lower), vclt_s32(northwest, upper));
  const uint32x2_t maximum_difference = vmax_u32(
      vreinterpret_u32_s32(vabd_s32(north, northwest)),
      vreinterpret_u32_s32(vabd_s32(west, northwest)));
  const uint32x2_t near =
      vcle_u32(maximum_difference, vdup_n_u32(32u));
  const int32x2_t gradient = vsub_s32(vadd_s32(north, west), northwest);
  const int32x2_t average = vhadd_s32(north, west);
  const int32x2_t calculated = vbsl_s32(near, average, gradient);
  const int32x2_t base = vbsl_s32(choose_west, west, north);
  return vbsl_s32(calculate, calculated, base);
}

#if defined(OM1_PARALLEL_CODEC)
struct PredictorState {
  int32_t delayed_west;
  int32_t last_predictor;
};

__attribute__((always_inline)) inline bool reconstruct_scalar_residual(
    uint32_t row, uint32_t column, int32_t residual, uint16_t *samples,
    uint16_t northwest_delay[2], PredictorState *state, Error *error) {
  const int32_t west = column < 2u ? 0 : state->delayed_west;
  const uint16_t old_north = samples[column];
  int32_t north = row < 2u ? 0 : static_cast<int32_t>(old_north);
  const int32_t northwest =
      (row < 2u || column < 2u)
          ? 0
          : static_cast<int32_t>(northwest_delay[column & 1u]);
  northwest_delay[column & 1u] = old_north;
  state->delayed_west = state->last_predictor;
  north = adjust_predictor(north, west, northwest);
  const int32_t high = residual + north;
  state->last_predictor = high;
  if (static_cast<uint32_t>(high) > 65535u)
    return fail(error, "ORF decoded sample is outside uint16 range");
  samples[column] = static_cast<uint16_t>(high);
  return true;
}

// Every row after the first two has a real north predictor.  Keeping that
// overwhelmingly common loop in its own function removes the per-pair row
// test and lets the loop terminate on the residual pointer directly.
__attribute__((noinline, hot, optimize("O3"))) bool
reconstruct_residual_pairs_with_north(
    const int32_t *residual_cursor, uint16_t *sample_cursor,
    const int32_t *residual_end, int32x2_t west, int32x2_t northwest,
    int32x2_t histories, Error *error) {
  const uint32x2_t maximum = vdup_n_u32(65535u);
  for (; residual_cursor < residual_end;
       residual_cursor += 2u, sample_cursor += 2u) {
    const uint32_t packed_north =
        reinterpret_cast<const PackedU32 *>(sample_cursor)->value;
    const uint16x4_t north16 =
        vreinterpret_u16_u32(vdup_n_u32(packed_north));
    const int32x2_t north =
        vreinterpret_s32_u32(vget_low_u32(vmovl_u16(north16)));
    const int32x2_t packed = vld1_s32(residual_cursor);
    const int32x2_t code_residual = vshr_n_s32(packed, 2);
    const int32x2_t history_scaled = vshl_n_s32(histories, 2);
    const int32x2_t residual = vadd_s32(packed, history_scaled);
    const int32x2_t unrefined = vbic_s32(residual, vdup_n_s32(3));
    histories = vshr_n_s32(vsub_s32(unrefined, code_residual), 5);
    const int32x2_t high =
        vadd_s32(residual, predict_neon_pair(north, west, northwest));
    const uint32x2_t invalid =
        vcgt_u32(vreinterpret_u32_s32(high), maximum);
    if (__builtin_expect(
            (vget_lane_u32(invalid, 0) |
             vget_lane_u32(invalid, 1)) != 0u,
            0))
      return fail(error, "ORF decoded sample is outside uint16 range");

    const uint16x4_t narrowed = vmovn_u32(vcombine_u32(
        vreinterpret_u32_s32(high), vreinterpret_u32_s32(high)));
    reinterpret_cast<PackedU32 *>(sample_cursor)->value =
        vget_lane_u32(vreinterpret_u32_u16(narrowed), 0);
    west = high;
    northwest = north;
  }
  return true;
}

__attribute__((hot, optimize("O3"))) bool reconstruct_residual_row_neon(
    uint32_t row, const ResidualRowSlot &slot, uint32_t width,
    PredictorState *state, Error *error) {
  const int32_t *const residuals = slot.values;
  uint16_t *const samples = g_rows[row & 1u];
  uint16_t northwest_delay[2] = {};
  if (!reconstruct_scalar_residual(row, 0u, residuals[0], samples,
                                   northwest_delay, state, error) ||
      !reconstruct_scalar_residual(row, 1u, residuals[1], samples,
                                   northwest_delay, state, error))
    return false;

  const int32x2_t zero = vdup_n_s32(0);
  int32x2_t west = vdup_n_s32(state->delayed_west);
  west = vset_lane_s32(state->last_predictor, west, 1);
  int32x2_t northwest = zero;
  if (row >= 2u) {
    northwest = vdup_n_s32(
        static_cast<int32_t>(northwest_delay[0]));
    northwest = vset_lane_s32(
        static_cast<int32_t>(northwest_delay[1]), northwest, 1);
  }
  const uint32x2_t maximum = vdup_n_u32(65535u);
  int32x2_t histories = vld1_s32(slot.initial_histories);

  if (row >= 2u) {
    return reconstruct_residual_pairs_with_north(
        residuals + 2u, samples + 2u, residuals + width, west, northwest,
        histories, error);
  }

  for (uint32_t column = 2u; column < width; column += 2u) {
    int32x2_t north = zero;
    const int32x2_t packed = vld1_s32(residuals + column);
    const int32x2_t code_residual = vshr_n_s32(packed, 2);
    const int32x2_t history_scaled = vshl_n_s32(histories, 2);
    const int32x2_t residual = vadd_s32(packed, history_scaled);
    const int32x2_t unrefined =
        vbic_s32(residual, vdup_n_s32(3));
    histories =
        vshr_n_s32(vsub_s32(unrefined, code_residual), 5);
    const int32x2_t high =
        vadd_s32(residual, predict_neon_pair(north, west, northwest));
    const uint32x2_t invalid =
        vcgt_u32(vreinterpret_u32_s32(high), maximum);
    if (__builtin_expect(
            (vget_lane_u32(invalid, 0) |
             vget_lane_u32(invalid, 1)) != 0u,
            0))
      return fail(error, "ORF decoded sample is outside uint16 range");

    const uint16x4_t narrowed = vmovn_u32(vcombine_u32(
        vreinterpret_u32_s32(high), vreinterpret_u32_s32(high)));
    reinterpret_cast<PackedU32 *>(samples + column)->value =
        vget_lane_u32(vreinterpret_u32_u16(narrowed), 0);
    west = high;
    northwest = north;
  }
  return true;
}
#endif

__attribute__((hot, optimize("O3"))) bool decode_standard_pixels_neon(
    PayloadReader *reader, const RawInfo &info, RowConsumer consumer,
    void *consumer_context, Error *error) {
  ScalarCodecState scalar = {};
  const int32x2_t zero = vdup_n_s32(0);
  const int32x2_t one = vdup_n_s32(1);
  const int32x2_t negative_thirty_two = vdup_n_s32(-32);

  for (uint32_t row = 0; row < info.height; ++row) {
    uint16_t *samples = g_rows[row & 1u];
    uint16_t northwest_delay[2] = {};

    if (row < 2u) {
      for (uint32_t column = 0; column < info.width; ++column) {
        if (!decode_scalar_pixel(reader, row, column, samples,
                                 northwest_delay, &scalar, error))
          return false;
      }
    } else {
      if (!decode_scalar_pixel(reader, row, 0, samples, northwest_delay,
                               &scalar, error) ||
          !decode_scalar_pixel(reader, row, 1, samples, northwest_delay,
                               &scalar, error))
        return false;
      const uint8_t *bit_cursor = reader->cursor;
      const uint8_t *bit_end = reader->end;
      uint32_t bit_offset = reader->bit_offset;

      uint32x2_t magnitudes = vdup_n_u32(
          static_cast<uint32_t>(scalar.delayed_magnitude));
      magnitudes = vset_lane_u32(
          static_cast<uint32_t>(scalar.magnitude), magnitudes, 1);
      int32x2_t selectors = zero;
      int32x2_t histories = vdup_n_s32(scalar.delayed_refinement);
      histories = vset_lane_s32(scalar.context[0], histories, 1);
      int32x2_t west = vdup_n_s32(scalar.context[1]);
      west = vset_lane_s32(scalar.last_predictor, west, 1);
      int32x2_t northwest =
          vdup_n_s32(static_cast<int32_t>(northwest_delay[0]));
      northwest = vset_lane_s32(
          static_cast<int32_t>(northwest_delay[1]), northwest, 1);

      for (uint32_t column = 2u; column < info.width; column += 2u) {
        selectors = vbsl_s32(
            vcle_u32(magnitudes, vdup_n_u32(16u)),
            vadd_s32(selectors, one), zero);
        const uint32x2_t lengths = vsub_u32(
            vdup_n_u32(32u),
            vclz_u32(vand_u32(magnitudes, vdup_n_u32(0xffffu))));
        const uint32x2_t selected =
            vcge_s32(selectors, vdup_n_s32(3));
        const int32x2_t threshold1 =
            vbsl_s32(selected, vdup_n_s32(3), vdup_n_s32(7));
        const int32x2_t threshold2 =
            vbsl_s32(selected, vdup_n_s32(2), vdup_n_s32(4));
        const int32x2_t adjustment = vmax_s32(
            vsub_s32(vreinterpret_s32_u32(lengths), threshold1),
            vdup_n_s32(-1));
        const uint32x2_t widths = vreinterpret_u32_s32(vmin_s32(
            vadd_s32(vadd_s32(threshold2, adjustment), one),
            vdup_n_s32(15)));

        NeonCodePair code;
        if (__builtin_expect(
                static_cast<uint32_t>(bit_end - bit_cursor) < 9u, 0)) {
          reader->cursor = bit_cursor;
          reader->end = bit_end;
          reader->bit_offset = bit_offset;
          if (!decode_neon_code_pair_boundary(
                  reader, widths, negative_thirty_two, error))
            return false;
          bit_cursor = reader->cursor;
          bit_end = reader->end;
          bit_offset = reader->bit_offset;
          code = g_escape_pair;
        } else {
          (void)decode_neon_code_pair(bit_cursor, bit_offset, widths,
                                      negative_thirty_two, &code);
        }
        magnitudes = vreinterpret_u32_s32(code.magnitude);

        const int32x2_t residual = vadd_s32(
            vreinterpret_s32_u32(code.refine),
            vshl_n_s32(vadd_s32(histories, code.residual), 2));
        const int32x2_t refinement_value = vadd_s32(
            vadd_s32(vbic_s32(vshr_n_s32(residual, 1), one), histories),
            vshr_n_s32(residual, 2));
        histories = vshr_n_s32(refinement_value, 5);

        const uint32_t old_north_packed =
            reinterpret_cast<const PackedU32 *>(samples + column)->value;
        const uint16x4_t old_north16 =
            vreinterpret_u16_u32(vdup_n_u32(old_north_packed));
        const int32x2_t north = vreinterpret_s32_u32(
            vget_low_u32(vmovl_u16(old_north16)));
        const int32x2_t high = vadd_s32(
            residual, predict_neon_pair(north, west, northwest));
        const uint32x2_t invalid =
            vcgt_u32(vreinterpret_u32_s32(high), vdup_n_u32(65535u));
        if (__builtin_expect(
                (vget_lane_u32(invalid, 0) |
                 vget_lane_u32(invalid, 1)) != 0u,
                0))
          return fail(error, "ORF decoded sample is outside uint16 range");

        const uint16x4_t narrowed = vmovn_u32(vcombine_u32(
            vreinterpret_u32_s32(high), vreinterpret_u32_s32(high)));
        reinterpret_cast<PackedU32 *>(samples + column)->value =
            vget_lane_u32(vreinterpret_u32_u16(narrowed), 0);
        west = high;
        northwest = north;
      }
      reader->cursor = bit_cursor;
      reader->end = bit_end;
      reader->bit_offset = bit_offset;
    }

    if (!consumer(consumer_context, row, samples, info.width, error)) {
      if (error != nullptr && error->text[0] != '\0') return false;
      return fail(error, "ORF row consumer failed");
    }
  }
  return true;
}
#endif

__attribute__((hot, optimize("O3"))) bool decode_standard_pixels(
    PayloadReader *reader, const RawInfo &info, RowConsumer consumer,
    void *consumer_context, Error *error) {
#if defined(__ARM_NEON) && !defined(OM1_DISABLE_NEON_BITSTREAM)
  return decode_standard_pixels_neon(reader, info, consumer, consumer_context,
                                     error);
#else
  return decode_standard_pixels_scalar(reader, info, consumer,
                                       consumer_context, error);
#endif
}

struct DiagnosticsConsumer {
  RowConsumer consumer;
  void *consumer_context;
  DecodeStats *stats;
};

// Checksums are a host-validation aid, not part of star detection.  Keeping
// this scan in a consumer wrapper leaves the production decoder with one
// compact hot loop and no diagnostics branch or 64-bit multiply per sensel.
bool consume_with_diagnostics(void *opaque, uint32_t row,
                              const uint16_t *samples, uint32_t width,
                              Error *error) {
  DiagnosticsConsumer *diagnostics =
      static_cast<DiagnosticsConsumer *>(opaque);
  DecodeStats *stats = diagnostics->stats;
  for (uint32_t column = 0; column < width; ++column) {
    const uint16_t sample = samples[column];
    ++stats->pixels_decoded;
    stats->sample_sum += sample;
    stats->sample_fnv1a ^= static_cast<uint8_t>(sample);
    stats->sample_fnv1a *= 1099511628211ULL;
    stats->sample_fnv1a ^= static_cast<uint8_t>(sample >> 8);
    stats->sample_fnv1a *= 1099511628211ULL;
    if (sample < stats->sample_min) stats->sample_min = sample;
    if (sample > stats->sample_max) stats->sample_max = sample;
  }
  return diagnostics->consumer(diagnostics->consumer_context, row, samples,
                               width, error);
}

struct ReductionConsumer {
  RawInfo *info;
  ReducedRowConsumer consumer;
  void *consumer_context;
};

__attribute__((always_inline)) inline uint32_t corrected_sample(
    uint16_t sample, uint16_t black, uint32_t white) {
  uint32_t bounded = sample;
  if (bounded > white) bounded = white;
  return bounded > black ? bounded - black : 0u;
}

// The predictor needs row-2, so both parity buffers remain live.  At the end
// of the second row in each active-crop pair, the other parity buffer is the
// complete preceding row; reduce the pair directly without an intermediate
// full-width aggregation buffer or a second raw-row consumer pass.
__attribute__((hot, optimize("O3"))) bool consume_reduced_raw_row(
    void *opaque, uint32_t row, const uint16_t *samples, uint32_t width,
    Error *error) {
  ReductionConsumer *reduction = static_cast<ReductionConsumer *>(opaque);
  const RawInfo &info = *reduction->info;
  if (width != info.width || row >= info.height)
    return fail(error, "ORF reduced-row input is invalid");
  if (row < info.crop_y || row - info.crop_y >= info.crop_height) return true;

  const uint32_t active_y = row - info.crop_y;
  if ((active_y & 1u) == 0u && active_y + 1u != info.crop_height)
    return true;

  const uint32_t source_rows = (active_y & 1u) != 0u ? 2u : 1u;
  const uint32_t top_row = source_rows == 2u ? row - 1u : row;
  const uint16_t *top =
      source_rows == 2u ? g_rows[top_row & 1u] : samples;
  const uint16_t *bottom = samples;
  const uint32_t first_x = info.crop_x;
  const uint32_t full_cells = info.crop_width >> 1;
  const uint32_t white =
      info.valid_bits == 16u ? 65535u : (1u << info.valid_bits) - 1u;
  const uint32_t top_phase = ((top_row & 1u) << 1) | (first_x & 1u);
  const uint16_t top_black_left = info.black[top_phase];
  const uint16_t top_black_right = info.black[top_phase ^ 1u];
  const uint16_t bottom_black_left = info.black[top_phase ^ 2u];
  const uint16_t bottom_black_right = info.black[top_phase ^ 3u];

  const uint16_t *top_source = top + first_x;
  const uint16_t *bottom_source = bottom + first_x;
  uint32_t cell = 0;
#if defined(__ARM_NEON)
  if (source_rows == 2u) {
    const uint16x4x2_t top_black_zip =
        vzip_u16(vdup_n_u16(top_black_left),
                 vdup_n_u16(top_black_right));
    const uint16x8_t top_black =
        vcombine_u16(top_black_zip.val[0], top_black_zip.val[1]);
    const uint16x4x2_t bottom_black_zip =
        vzip_u16(vdup_n_u16(bottom_black_left),
                 vdup_n_u16(bottom_black_right));
    const uint16x8_t bottom_black =
        vcombine_u16(bottom_black_zip.val[0], bottom_black_zip.val[1]);
    const uint16x8_t white_vector =
        vdupq_n_u16(static_cast<uint16_t>(white));

    for (; cell + 8u <= full_cells; cell += 8u) {
      const uint32_t column = cell << 1;
      const uint16x8_t top0 = vld1q_u16(top_source + column);
      const uint16x8_t top1 = vld1q_u16(top_source + column + 8u);
      const uint16x8_t bottom0 = vld1q_u16(bottom_source + column);
      const uint16x8_t bottom1 =
          vld1q_u16(bottom_source + column + 8u);
      const uint16x8_t corrected_top0 =
          vqsubq_u16(vminq_u16(top0, white_vector), top_black);
      const uint16x8_t corrected_top1 =
          vqsubq_u16(vminq_u16(top1, white_vector), top_black);
      const uint16x8_t corrected_bottom0 =
          vqsubq_u16(vminq_u16(bottom0, white_vector), bottom_black);
      const uint16x8_t corrected_bottom1 =
          vqsubq_u16(vminq_u16(bottom1, white_vector), bottom_black);
      vst1q_u32(g_reduced_sums + cell,
                vaddq_u32(vpaddlq_u16(corrected_top0),
                          vpaddlq_u16(corrected_bottom0)));
      vst1q_u32(g_reduced_sums + cell + 4u,
                vaddq_u32(vpaddlq_u16(corrected_top1),
                          vpaddlq_u16(corrected_bottom1)));

      const uint16x8_t clipped0 =
          vorrq_u16(vcgeq_u16(top0, white_vector),
                    vcgeq_u16(bottom0, white_vector));
      const uint16x8_t clipped1 =
          vorrq_u16(vcgeq_u16(top1, white_vector),
                    vcgeq_u16(bottom1, white_vector));
      const uint16x4_t clipped_pairs0 =
          vpmax_u16(vget_low_u16(clipped0), vget_high_u16(clipped0));
      const uint16x4_t clipped_pairs1 =
          vpmax_u16(vget_low_u16(clipped1), vget_high_u16(clipped1));
      vst1_u8(g_reduced_saturated + cell,
              vshr_n_u8(
                  vmovn_u16(vcombine_u16(clipped_pairs0, clipped_pairs1)),
                  7));
    }
  }
#endif
  for (; cell < full_cells; ++cell) {
    const uint32_t column = cell << 1;
    const uint16_t top_left = top_source[column];
    const uint16_t top_right = top_source[column + 1u];
    uint32_t sum = corrected_sample(top_left, top_black_left, white) +
                   corrected_sample(top_right, top_black_right, white);
    bool saturated = top_left >= white || top_right >= white;
    if (source_rows == 2u) {
      const uint16_t bottom_left = bottom_source[column];
      const uint16_t bottom_right = bottom_source[column + 1u];
      sum += corrected_sample(bottom_left, bottom_black_left, white) +
             corrected_sample(bottom_right, bottom_black_right, white);
      saturated = saturated || bottom_left >= white || bottom_right >= white;
    }
    g_reduced_sums[cell] = sum;
    g_reduced_saturated[cell] = saturated ? 1u : 0u;
  }

  uint32_t reduced_width = full_cells;
  if ((info.crop_width & 1u) != 0u) {
    const uint32_t column = full_cells << 1;
    const uint16_t top_sample = top_source[column];
    uint32_t sum = corrected_sample(top_sample, top_black_left, white);
    bool saturated = top_sample >= white;
    if (source_rows == 2u) {
      const uint16_t bottom_sample = bottom_source[column];
      sum += corrected_sample(bottom_sample, bottom_black_left, white);
      saturated = saturated || bottom_sample >= white;
    }
    g_reduced_sums[full_cells] = sum;
    g_reduced_saturated[full_cells] = saturated ? 1u : 0u;
    ++reduced_width;
  }

  return reduction->consumer(reduction->consumer_context, active_y >> 1,
                             g_reduced_sums, g_reduced_saturated,
                             reduced_width, error);
}

#if defined(OM1_PARALLEL_CODEC)
void update_parallel_stats(DecodeStats *stats, const uint16_t *samples,
                           uint32_t width) {
  if (stats == nullptr) return;
  for (uint32_t column = 0; column < width; ++column) {
    const uint16_t sample = samples[column];
    ++stats->pixels_decoded;
    stats->sample_sum += sample;
    stats->sample_fnv1a ^= static_cast<uint8_t>(sample);
    stats->sample_fnv1a *= 1099511628211ULL;
    stats->sample_fnv1a ^= static_cast<uint8_t>(sample >> 8);
    stats->sample_fnv1a *= 1099511628211ULL;
    if (sample < stats->sample_min) stats->sample_min = sample;
    if (sample > stats->sample_max) stats->sample_max = sample;
  }
}

void fail_parallel_worker(ParallelCodecState *pipeline) {
  if (pipeline->worker_error.text[0] == '\0')
    (void)fail(&pipeline->worker_error, "ORF codec worker failed");
  __atomic_store_n(&pipeline->worker_failed, 1u, __ATOMIC_RELEASE);
  Error signal_error = {};
  (void)notify_event(&pipeline->space_event, &signal_error);
}

void reconstruct_worker(void *opaque) {
  ParallelCodecState *const pipeline =
      static_cast<ParallelCodecState *>(opaque);
  PredictorState predictor = {};
  ReductionConsumer reduction = {&pipeline->info, pipeline->consumer,
                                 pipeline->consumer_context};

  for (;;) {
    if (__atomic_load_n(&pipeline->cancelled, __ATOMIC_ACQUIRE) != 0u)
      return;
    const uint32_t consumed =
        __atomic_load_n(&pipeline->consumed, __ATOMIC_RELAXED);
    const uint32_t produced =
        __atomic_load_n(&pipeline->produced, __ATOMIC_ACQUIRE);
    if (consumed != produced) {
      const ResidualRowSlot &slot =
          pipeline->rows[consumed % kResidualRingRows];
      if (!reconstruct_residual_row_neon(consumed, slot,
                                         pipeline->info.width, &predictor,
                                         &pipeline->worker_error)) {
        fail_parallel_worker(pipeline);
        return;
      }
      const uint16_t *const samples = g_rows[consumed & 1u];
      update_parallel_stats(pipeline->stats, samples, pipeline->info.width);
      if (!consume_reduced_raw_row(&reduction, consumed, samples,
                                   pipeline->info.width,
                                   &pipeline->worker_error)) {
        if (pipeline->worker_error.text[0] == '\0')
          (void)fail(&pipeline->worker_error,
                     "ORF reduced-row consumer failed");
        fail_parallel_worker(pipeline);
        return;
      }
      __atomic_store_n(&pipeline->consumed, consumed + 1u,
                       __ATOMIC_RELEASE);
      if (!notify_registered_waiter(&pipeline->space_event,
                                    &pipeline->space_waiting,
                                    &pipeline->worker_error)) {
        fail_parallel_worker(pipeline);
        return;
      }
      continue;
    }
    if (__atomic_load_n(&pipeline->input_done, __ATOMIC_ACQUIRE) != 0u) {
      // The producer can publish the final row between the produced snapshot
      // above and input_done.  Reload after acquiring done so that the worker
      // drains that row instead of returning on the stale snapshot.
      if (__atomic_load_n(&pipeline->produced, __ATOMIC_ACQUIRE) == consumed)
        return;
      continue;
    }

    const uint32_t event =
        __atomic_load_n(&pipeline->data_event, __ATOMIC_ACQUIRE);
    __atomic_store_n(&pipeline->data_waiting, 1u, __ATOMIC_RELEASE);
    if (__atomic_load_n(&pipeline->cancelled, __ATOMIC_ACQUIRE) != 0u ||
        __atomic_load_n(&pipeline->produced, __ATOMIC_ACQUIRE) != consumed ||
        __atomic_load_n(&pipeline->input_done, __ATOMIC_ACQUIRE) != 0u) {
      __atomic_store_n(&pipeline->data_waiting, 0u, __ATOMIC_RELEASE);
      continue;
    }
    const bool waited = wait_private_word(&pipeline->data_event, event,
                                          &pipeline->worker_error);
    __atomic_store_n(&pipeline->data_waiting, 0u, __ATOMIC_RELEASE);
    if (!waited) {
      fail_parallel_worker(pipeline);
      return;
    }
  }
}

bool stop_parallel_worker(ParallelCodecState *pipeline, bool cancelled,
                          Error *error) {
  if (cancelled)
    __atomic_store_n(&pipeline->cancelled, 1u, __ATOMIC_RELEASE);
  else
    __atomic_store_n(&pipeline->input_done, 1u, __ATOMIC_RELEASE);

  Error signal_error = {};
  const bool signalled =
      notify_event(&pipeline->data_event, &signal_error);
  Error join_error = {};
  const bool joined = join_fixed_thread(&g_codec_thread, &join_error);
  // Returning with a possibly live CLONE_VM worker would make every static
  // buffer and callback pointer unsafe to reuse.  A hard join-futex failure
  // is therefore process-fatal; ordinary timeout/EINTR/EAGAIN results are
  // retried inside join_fixed_thread().
  if (!joined) os::exit_group(125);
  if (!signalled && error != nullptr && error->text[0] == '\0')
    copy_error_value(error, signal_error);
  return signalled;
}

__attribute__((noinline, hot, optimize("O3"))) bool decode_strip_parallel(
    InputStream *input, const RawInfo &info, ReducedRowConsumer consumer,
    void *consumer_context, DecodeStats *stats, Error *error) {
  PayloadReader reader = {input, info.strip_bytes, g_prefix, g_prefix, 0, 0};
  uint64_t header = 0;
  for (uint32_t i = 0; i < info.codec[19]; ++i) {
    uint8_t byte;
    if (!reader.byte(&byte, error)) return false;
    header = (header << 8) | byte;
  }
  uint8_t zero1;
  uint8_t zero2;
  if (!reader.byte(&zero1, error) || !reader.byte(&zero2, error)) return false;
  if (header != 1 || zero1 != 0 || zero2 != 0)
    return fail(error, "ORF compressed strip prologue is invalid");

  ParallelCodecState *const pipeline = &g_parallel_codec;
  __atomic_store_n(&pipeline->produced, 0u, __ATOMIC_RELAXED);
  __atomic_store_n(&pipeline->consumed, 0u, __ATOMIC_RELAXED);
  __atomic_store_n(&pipeline->data_event, 0u, __ATOMIC_RELAXED);
  __atomic_store_n(&pipeline->space_event, 0u, __ATOMIC_RELAXED);
  __atomic_store_n(&pipeline->data_waiting, 0u, __ATOMIC_RELAXED);
  __atomic_store_n(&pipeline->space_waiting, 0u, __ATOMIC_RELAXED);
  __atomic_store_n(&pipeline->input_done, 0u, __ATOMIC_RELAXED);
  __atomic_store_n(&pipeline->cancelled, 0u, __ATOMIC_RELAXED);
  __atomic_store_n(&pipeline->worker_failed, 0u, __ATOMIC_RELAXED);
  pipeline->info = info;
  pipeline->consumer = consumer;
  pipeline->consumer_context = consumer_context;
  pipeline->stats = stats;
  clear_error(&pipeline->worker_error);

  if (!start_fixed_thread(&g_codec_thread, reconstruct_worker, pipeline,
                          g_codec_thread_stack,
                          sizeof(g_codec_thread_stack), error))
    return false;

  bool producer_ok =
      decode_residual_rows_neon(&reader, info, pipeline, error);
  if (producer_ok) producer_ok = reader.drain(error);
  if (producer_ok && reader.bytes_read != info.strip_bytes)
    producer_ok = fail(error, "ORF strip was not read exactly once");

  if (!stop_parallel_worker(pipeline, !producer_ok, error)) return false;
  if (!producer_ok) return false;
  if (__atomic_load_n(&pipeline->worker_failed, __ATOMIC_ACQUIRE) != 0u) {
    copy_error_value(error, pipeline->worker_error);
    return false;
  }
  if (__atomic_load_n(&pipeline->consumed, __ATOMIC_ACQUIRE) != info.height)
    return fail(error, "ORF codec worker did not consume every row");

  if (stats != nullptr) {
    stats->strip_bytes_read = reader.bytes_read;
    stats->raw_passes = 1;
  }
  return true;
}
#endif

__attribute__((noinline, hot, optimize("O3"))) bool decode_strip(
    InputStream *input, const RawInfo &info, RowConsumer consumer,
    void *consumer_context, DecodeStats *stats, Error *error) {
  PayloadReader reader = {input, info.strip_bytes, g_prefix, g_prefix, 0, 0};
  uint64_t header = 0;
  for (uint32_t i = 0; i < info.codec[19]; ++i) {
    uint8_t byte;
    if (!reader.byte(&byte, error)) return false;
    header = (header << 8) | byte;
  }
  uint8_t zero1;
  uint8_t zero2;
  if (!reader.byte(&zero1, error) || !reader.byte(&zero2, error)) return false;
  if (header != 1 || zero1 != 0 || zero2 != 0)
    return fail(error, "ORF compressed strip prologue is invalid");

  DiagnosticsConsumer diagnostics = {consumer, consumer_context, stats};
  if (stats != nullptr) {
    consumer = consume_with_diagnostics;
    consumer_context = &diagnostics;
  }
  if (!decode_standard_pixels(&reader, info, consumer, consumer_context,
                              error))
    return false;

  if (!reader.drain(error)) return false;
  if (reader.bytes_read != info.strip_bytes)
    return fail(error, "ORF strip was not read exactly once");
  // The sole payload traversal is now complete.  This is deliberately set
  // only after all rows and any tagged strip padding have been consumed.
  if (stats != nullptr) {
    stats->strip_bytes_read = reader.bytes_read;
    stats->raw_passes = 1;
  }
  return true;
}

bool prepare_orf(InputStream *input, RawInfo *raw_info, DecodeStats *stats,
                 Error *error) {
  clear_error(error);
  g_metadata_prefix_loaded = 0;
  RawInfo info = {};
  *raw_info = {};
  if (stats != nullptr) {
    *stats = {};
    stats->sample_fnv1a = 14695981039346656037ULL;
    stats->sample_min = 65535;
  }
  if (input->total_size < 8)
    return fail(error, "ORF input is shorter than its header");

  uint32_t prefix_size = 0;
  if (!ensure_prefix(input, &prefix_size, 8, error)) return false;
  if (g_prefix[0] != 'I' || g_prefix[1] != 'I' ||
      load_u16(2) != 0x4f52 || load_u32(4) != 8)
    return fail(error, "ORF header is not regular little-endian OM-1");

  if (!ensure_prefix(input, &prefix_size, 10, error)) return false;
  const uint16_t ifd0_count = load_u16(8);
  if (ifd0_count != sizeof(kIfd0Schema) / sizeof(kIfd0Schema[0]))
    return fail(error, "IFD0 does not match regular OM-1 schema");
  const uint32_t ifd0_end = 8u + 2u + 12u * ifd0_count + 4u;
  if (!ensure_prefix(input, &prefix_size, ifd0_end, error)) return false;

  Ifd early_ifd0 = {8, 0, prefix_size, ifd0_count};
  if (load_u32(8u + 2u + 12u * ifd0_count) != 0)
    return fail(error, "ORF has an unsupported linked directory");
  if (!validate_ifd0_schema(early_ifd0, error)) return false;
  Entry strip_entry;
  Entry bytes_entry;
  if (!find_entry(early_ifd0, 0x0111, &strip_entry, error) ||
      !entry_u32(strip_entry, &info.strip_offset, error) ||
      !find_entry(early_ifd0, 0x0117, &bytes_entry, error) ||
      !entry_u32(bytes_entry, &info.strip_bytes, error))
    return false;
  if (info.strip_offset < ifd0_end ||
      info.strip_offset > kMaximumMetadataExtent)
    return fail(error, "ORF StripOffset is outside the metadata capacity");
  if (info.strip_offset > input->total_size ||
      info.strip_bytes != input->total_size - info.strip_offset)
    return fail(error, "ORF strip does not end at the file boundary");
  const uint32_t expected_strip_offset = info.strip_offset;
  const uint32_t expected_strip_bytes = info.strip_bytes;

  uint32_t retained_prefix = expected_strip_offset;
  if (retained_prefix > kMetadataPrefixCapacity)
    retained_prefix = kMetadataPrefixCapacity;
  if (!ensure_prefix(input, &prefix_size, retained_prefix, error) ||
      !parse_metadata(expected_strip_offset, &info, error))
    return false;
  if (info.strip_offset != expected_strip_offset ||
      info.strip_bytes != expected_strip_bytes)
    return fail(error, "ORF strip bounds changed while parsing metadata");
  if (!skip_exact(input, expected_strip_offset - prefix_size, error))
    return false;
  if (info.strip_bytes < static_cast<uint32_t>(info.codec[19]) + 2u)
    return fail(error, "ORF compressed strip is too short");

  *raw_info = info;
  return true;
}

}  // namespace

bool process_orf(InputStream *input, RowConsumer consumer,
                 void *consumer_context, RawInfo *raw_info,
                 DecodeStats *stats, Error *error) {
  if (input == nullptr || input->read == nullptr || consumer == nullptr ||
      raw_info == nullptr)
    return fail(error, "process_orf received a null argument");
  if (!prepare_orf(input, raw_info, stats, error) ||
      !decode_strip(input, *raw_info, consumer, consumer_context, stats,
                    error))
    return false;
  clear_error(error);
  return true;
}

bool process_orf_reduced(InputStream *input, ReducedRowConsumer consumer,
                         void *consumer_context, RawInfo *raw_info,
                         DecodeStats *stats, Error *error) {
  if (consumer == nullptr || raw_info == nullptr)
    return fail(error, "process_orf_reduced received a null argument");
  if (input == nullptr || input->read == nullptr)
    return fail(error, "process_orf_reduced received a null argument");
  if (!prepare_orf(input, raw_info, stats, error)) return false;
#if defined(OM1_PARALLEL_CODEC)
  if (!decode_strip_parallel(input, *raw_info, consumer, consumer_context,
                             stats, error))
    return false;
#else
  ReductionConsumer reduction = {raw_info, consumer, consumer_context};
  if (!decode_strip(input, *raw_info, consume_reduced_raw_row, &reduction,
                    stats, error))
    return false;
#endif
  clear_error(error);
  return true;
}

}  // namespace om1_stars
