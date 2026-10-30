#![allow(dead_code)]
#[path = "../src/abi.rs"]
mod abi;
#[path = "../src/digest.rs"]
mod digest;
#[path = "../src/loader.rs"]
mod loader;
#[path = "../src/runtime.rs"]
mod runtime;
#[path = "abi.rs"]
mod scissor_abi;
#[path = "runtime.rs"]
mod scissor_runtime;
use scissor_abi::{Api, Header, Pixels, Project};
use std::{
    fs,
    path::{Path, PathBuf},
};

struct Host {
    images: Vec<loader::Library>,
    active: Option<(u64, Project)>,
    completed: u64,
    output: Box<scissor_runtime::Guarded>,
    address: usize,
}
impl Host {
    fn new() -> Self {
        let output = Box::new(scissor_runtime::Guarded::new());
        let address = &output.output as *const Pixels as usize;
        Self {
            images: Vec::new(),
            active: None,
            completed: 0,
            output,
            address,
        }
    }
    fn select(
        &mut self,
        path: &Path,
        hash: &str,
        generation: u64,
        inset: bool,
    ) -> Result<(), &'static str> {
        self.images
            .push(loader::Library::open_verified(path, hash)?);
        let symbol = self.images.last().unwrap().symbol(c"rq_scissor_api");
        if symbol.is_null() {
            return Err("entry symbol");
        }
        let entry: unsafe extern "C" fn() -> *const Header = unsafe { std::mem::transmute(symbol) };
        let pointer = unsafe { entry() };
        if pointer.is_null() || pointer as usize % std::mem::align_of::<Header>() != 0 {
            return Err("header pointer");
        }
        let header = unsafe { pointer.read() };
        scissor_abi::validate(header, generation)?;
        if self.active.is_some_and(|(old, _)| generation <= old) {
            return Err("stale generation");
        }
        let function = unsafe { &*pointer.cast::<Api>() }
            .project
            .ok_or("null project")?;
        scissor_runtime::probe(function, inset)?;
        if digest::sha256(&fs::read(path).map_err(|_| "artifact reread")?) != hash {
            return Err("artifact changed");
        }
        self.active = Some((generation, function));
        Ok(())
    }
    fn project(&mut self, rect: [f32; 4]) -> Result<(), &'static str> {
        let function = self.active.ok_or("no selected generation")?.1;
        scissor_runtime::dispatch(
            function,
            &mut self.completed,
            &mut self.output,
            1920,
            1080,
            rect,
        )?;
        if self.address != &self.output.output as *const Pixels as usize {
            return Err("host allocation changed");
        }
        Ok(())
    }
    fn observe(&self, event: &str) {
        let p = self.output.output;
        println!("{{\"event\":\"{event}\",\"completed\":{},\"generation\":{},\"retained_handles\":{},\"pixels\":[{},{},{},{}],\"allocation\":1}}",self.completed,self.active.map_or(0,|v|v.0),self.images.len(),p.x,p.y,p.width,p.height);
    }
}
struct Artifact {
    name: String,
    generation: u64,
    hash: String,
    path: PathBuf,
}
fn run() -> Result<(), String> {
    let manifest = std::env::args().nth(1).ok_or("manifest path required")?;
    let manifest = fs::canonicalize(manifest).map_err(|e| e.to_string())?;
    let mut files = Vec::new();
    for line in fs::read_to_string(&manifest)
        .map_err(|e| e.to_string())?
        .lines()
    {
        let f: Vec<_> = line.split('\t').collect();
        if f.len() != 4 {
            return Err("manifest fields".into());
        }
        files.push(Artifact {
            name: f[0].into(),
            generation: f[1].parse().map_err(|_| "generation")?,
            hash: f[2].into(),
            path: fs::canonicalize(manifest.parent().unwrap().join(f[3]))
                .map_err(|e| e.to_string())?,
        });
    }
    let names: Vec<_> = files.iter().map(|v| v.name.as_str()).collect();
    if names
        != [
            "a1",
            "b2",
            "a3",
            "wrong-version",
            "short-table",
            "wrong-contract",
            "bad-behavior",
            "null-function",
        ]
    {
        return Err("manifest inventory".into());
    }
    let mut host = Host::new();
    host.observe("fresh");
    let a = &files[0];
    host.select(&a.path, &a.hash, a.generation, false)?;
    host.project([0.1, 0.2, 0.55, 0.5])?;
    host.observe("a1");
    let saved = (host.completed, host.output.output, host.active.unwrap().0);
    if host.project([f32::NAN, 0., 1., 1.]).is_ok()
        || (host.completed, host.output.output, host.active.unwrap().0) != saved
    {
        return Err("invalid call changed host".into());
    }
    for f in &files[3..] {
        if host.select(&f.path, &f.hash, f.generation, false).is_ok()
            || (host.completed, host.output.output, host.active.unwrap().0) != saved
        {
            return Err("candidate refusal changed host".into());
        }
    }
    if host.images.len() != 6 {
        return Err("rejected images not retained".into());
    }
    let b = &files[1];
    if host
        .select(&b.path, &"0".repeat(64), b.generation, true)
        .is_ok()
        || host.images.len() != 6
    {
        return Err("bad digest loaded".into());
    }
    host.project([0.1, 0.2, 0.55, 0.5])?;
    host.observe("a1-after-rejections");
    host.select(&b.path, &b.hash, b.generation, true)?;
    host.project([0.1, 0.2, 0.55, 0.5])?;
    if host.output.output
        != (Pixels {
            x: 193,
            y: 217,
            width: 1054,
            height: 538,
        })
    {
        return Err("B did not change pixels".into());
    }
    host.observe("b2");
    let a3 = &files[2];
    host.select(&a3.path, &a3.hash, a3.generation, false)?;
    host.project([0.1, 0.2, 0.55, 0.5])?;
    if host.output.output != saved.1 || host.completed != 4 || host.images.len() != 8 {
        return Err("fresh A rollback differed".into());
    }
    host.observe("a3-rollback");
    let saved = (host.completed, host.output.output, host.active.unwrap().0);
    if host.select(&b.path, &b.hash, b.generation, true).is_ok()
        || (host.completed, host.output.output, host.active.unwrap().0) != saved
        || host.images.len() != 9
    {
        return Err("stale generation changed host".into());
    }
    host.observe("stale-refused");
    println!("SCISSOR_PROOF_PASSED");
    Ok(())
}
fn main() {
    if let Err(error) = run() {
        eprintln!("{error}");
        std::process::exit(1);
    }
}
