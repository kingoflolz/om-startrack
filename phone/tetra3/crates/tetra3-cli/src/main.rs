mod builder;
mod catalog;
mod cli;
mod error;
mod fov;
mod fpr;
mod geometry;
mod io_utils;
mod lensfun_xml;
mod rng;
mod validate;
mod validation_common;

use error::{Error, Result};

fn main() {
    if let Err(error) = cli::run() {
        eprintln!("tetra3: {error}");
        std::process::exit(1);
    }
}
