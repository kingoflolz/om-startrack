use core::fmt;
use std::fs::File;
use std::io;

use memmap2::{Mmap, MmapOptions};

use crate::{Error, IndexView};

/// Owns a read-only mapping while yielding zero-copy [`IndexView`] values.
///
/// Android runtime code should use [`Self::map_trusted_region`] after the index
/// has been fully checked during build or installation. That path validates
/// bounds without CRC-walking the bulk payload, preserving demand paging.
pub struct MappedIndex {
    mapping: Mmap,
    level_count: u16,
}

#[derive(Debug)]
pub enum MapError {
    Io(io::Error),
    Format(Error),
}

impl fmt::Display for MapError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "failed to map index: {error}"),
            Self::Format(error) => write!(formatter, "invalid mapped index: {error}"),
        }
    }
}

impl std::error::Error for MapError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            Self::Format(error) => Some(error),
        }
    }
}

impl MappedIndex {
    /// Map a file region and take the fast, preverified-index path.
    ///
    /// This matches Android's uncompressed APK asset contract: pass the asset
    /// file descriptor together with the asset offset and exact length.
    ///
    /// # Safety
    ///
    /// The mapped region must not be truncated or modified for the lifetime of
    /// the returned value. This is normally guaranteed by an immutable APK or
    /// by atomically installing a read-only index before opening it.
    pub unsafe fn map_trusted_region(
        file: &File,
        offset: u64,
        length: usize,
    ) -> Result<Self, MapError> {
        // SAFETY: the caller promises that the file region remains immutable
        // and at least `length` bytes long for the lifetime of the mapping.
        let mapping = unsafe {
            MmapOptions::new()
                .offset(offset)
                .len(length)
                .map(file)
                .map_err(MapError::Io)?
        };
        let level_count = IndexView::parse_trusted_mapped(&mapping)
            .map_err(MapError::Format)?
            .level_count();
        Ok(Self {
            mapping,
            level_count,
        })
    }

    pub fn from_mmap(mapping: Mmap) -> Result<Self, Error> {
        let level_count = IndexView::parse(&mapping)?.level_count();
        Ok(Self {
            mapping,
            level_count,
        })
    }

    pub fn view(&self) -> IndexView<'_> {
        // Construction already validated the immutable mapping; borrowing it
        // again cannot invalidate any of those checks.
        IndexView::from_validated_parts(&self.mapping, self.level_count)
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.mapping
    }
}

impl fmt::Debug for MappedIndex {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("MappedIndex")
            .field("length", &self.mapping.len())
            .finish()
    }
}
