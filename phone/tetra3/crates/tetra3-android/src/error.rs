use std::{fmt, io};

#[derive(Debug)]
pub(crate) enum BridgeError {
    InvalidArgument(String),
    Io(io::Error),
    Index(String),
    Lens(String),
    AmbiguousLens(Vec<String>),
    Solve(String),
    StaleHandle,
    TooManyEngines,
    Jni(String),
    Panic,
}

impl BridgeError {
    pub(crate) fn invalid(message: impl Into<String>) -> Self {
        Self::InvalidArgument(message.into())
    }
}

impl fmt::Display for BridgeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidArgument(message) => write!(formatter, "invalid argument: {message}"),
            Self::Io(error) => write!(formatter, "I/O error: {error}"),
            Self::Index(error) => write!(formatter, "index error: {error}"),
            Self::Lens(error) => write!(formatter, "lens error: {error}"),
            Self::AmbiguousLens(ids) => write!(
                formatter,
                "lens name matches profiles with different calibrations: {}",
                ids.join(", ")
            ),
            Self::Solve(error) => write!(formatter, "plate solve failed: {error}"),
            Self::StaleHandle => formatter.write_str("plate-solver handle is closed or invalid"),
            Self::TooManyEngines => formatter.write_str("too many plate-solver engines are open"),
            Self::Jni(error) => write!(formatter, "JNI error: {error}"),
            Self::Panic => formatter.write_str("native plate solver panicked"),
        }
    }
}

impl std::error::Error for BridgeError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            _ => None,
        }
    }
}

impl From<io::Error> for BridgeError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

impl From<jni::errors::Error> for BridgeError {
    fn from(error: jni::errors::Error) -> Self {
        Self::Jni(error.to_string())
    }
}
