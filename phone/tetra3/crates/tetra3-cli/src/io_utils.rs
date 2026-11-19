use std::fs::File;
use std::io::Write;
use std::path::Path;

use serde::Serialize;

use crate::{Error, Result};

pub(crate) fn write_file_atomic(
    path: &Path,
    write: impl FnOnce(&mut File) -> Result<()>,
) -> Result<()> {
    let parent = path
        .parent()
        .filter(|value| !value.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    std::fs::create_dir_all(parent).map_err(|error| Error::io(parent, error))?;
    let mut temporary =
        tempfile::NamedTempFile::new_in(parent).map_err(|error| Error::io(parent, error))?;
    write(temporary.as_file_mut())?;
    temporary
        .as_file_mut()
        .sync_all()
        .map_err(|error| Error::io(path, error))?;
    temporary
        .persist(path)
        .map_err(|error| Error::io(path, error.error))?;
    Ok(())
}

pub(crate) fn write_bytes_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    write_file_atomic(path, |file| {
        file.write_all(bytes)
            .map_err(|error| Error::io(path, error))
    })
}

pub(crate) fn write_json_atomic(path: &Path, value: &impl Serialize) -> Result<()> {
    write_file_atomic(path, |file| {
        serde_json::to_writer_pretty(&mut *file, value)
            .map_err(|error| Error::Serialization(error.to_string()))?;
        file.write_all(b"\n")
            .map_err(|error| Error::io(path, error))
    })
}
