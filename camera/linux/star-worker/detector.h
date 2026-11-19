#ifndef OM1_STARS_DETECTOR_H
#define OM1_STARS_DETECTOR_H

#include "types.h"

namespace om1_stars {

// The detector is a single, statically allocated streaming instance.  begin()
// starts one image, consume_row() must then receive every decoded raw row once
// in increasing raster order, and finish() closes the remaining components.
// "One pass" means exactly this one forward delivery of the decoded raw
// raster; robust statistics may revisit only the bounded in-memory slab.
// Internally only a bounded 32-row plane of 2x2-CFA luma cells is retained;
// neither the raw mosaic nor a full threshold mask is materialized.
bool detector_begin(const RawInfo &info, const DetectorOptions &options,
                    Error *error);
bool detector_consume_row(void *context, uint32_t row,
                          const uint16_t *samples, uint32_t width,
                          Error *error);

// Fused production entry point for a decoder which combines the 2x2 CFA
// reduction into raw reconstruction.  sums contains the unrounded sum of the
// black-corrected sensels in each 2x2 cell; saturated has one nonzero byte for
// every cell containing a clipped raw sensel.  Keeping sums unrounded lets the
// detector retain exact 1/2/4-sensel edge divisors for odd crops.  Rows and
// width use the ceil(crop/2) detector grid, while all Star coordinates and
// bounding boxes remain in active-crop raw-sensel units.
bool detector_consume_luma_row(void *context, uint32_t row,
                               const uint32_t *sums,
                               const uint8_t *saturated, uint32_t width,
                               Error *error);
bool detector_finish(Star out[kMaxStars], uint32_t *count, Error *error);

}  // namespace om1_stars

#endif
