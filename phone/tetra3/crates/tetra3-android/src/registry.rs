use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex, MutexGuard, OnceLock,
        atomic::{AtomicI64, Ordering},
    },
};

use crate::{engine::Engine, error::BridgeError};

const MAX_OPEN_ENGINES: usize = 8;
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static ENGINES: OnceLock<Mutex<HashMap<i64, Arc<Engine>>>> = OnceLock::new();

fn engines() -> MutexGuard<'static, HashMap<i64, Arc<Engine>>> {
    ENGINES
        .get_or_init(|| Mutex::new(HashMap::new()))
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

pub(crate) fn insert(engine: Engine) -> Result<i64, BridgeError> {
    let mut engines = engines();
    if engines.len() >= MAX_OPEN_ENGINES {
        return Err(BridgeError::TooManyEngines);
    }
    // Handles remain positive Java longs. Exhausting this counter would take
    // billions of years at any realistic open/close rate; fail instead of
    // wrapping into a value which could alias an earlier handle.
    let handle = NEXT_HANDLE
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |current| {
            (current > 0 && current < i64::MAX).then_some(current + 1)
        })
        .map_err(|_| BridgeError::TooManyEngines)?;
    engines.insert(handle, Arc::new(engine));
    Ok(handle)
}

pub(crate) fn get(handle: i64) -> Result<Arc<Engine>, BridgeError> {
    if handle <= 0 {
        return Err(BridgeError::StaleHandle);
    }
    engines()
        .get(&handle)
        .cloned()
        .ok_or(BridgeError::StaleHandle)
}

/// Remove a handle. This is intentionally idempotent for AutoCloseable cleanup.
/// Any solve which already cloned the Arc completes before the mapping drops.
pub(crate) fn close(handle: i64) {
    if handle > 0 {
        engines().remove(&handle);
    }
}
