use std::path::PathBuf;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("I/O error while accessing {path}: {source}")]
    Io {
        path: PathBuf,
        #[source]
        source: std::io::Error,
    },

    #[error("invalid catalog: {0}")]
    InvalidCatalog(String),

    #[error("invalid index: {0}")]
    InvalidIndex(String),

    #[error("invalid argument: {0}")]
    InvalidArgument(String),

    #[error("CSV row {row}: {message}")]
    Csv { row: u64, message: String },

    #[error("serialization failed: {0}")]
    Serialization(String),

    #[error("index construction failed: {0}")]
    Build(String),

    #[error("validation failed: {0}")]
    Validation(String),
}

impl Error {
    pub(crate) fn io(path: impl Into<PathBuf>, source: std::io::Error) -> Self {
        Self::Io {
            path: path.into(),
            source,
        }
    }
}

pub type Result<T, E = Error> = std::result::Result<T, E>;
