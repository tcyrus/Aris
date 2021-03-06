extern crate libaris;

// This file builds the headless version of Aris,
// meant for verifying proofs submitted on Submitty.
#[macro_use] extern crate frunk;
use std::env;
use std::path::Path;
use std::fs::File;
use std::collections::HashSet;

use libaris::proofs::Proof;
use libaris::expression::Expr;
use libaris::proofs::xml_interop::proof_from_xml;

// Takes 2 files as args:
// First one is instructor assignment
//   Should have 1 top level proof w/ an arbitrary number of assumptions, only 1 step
//
// Second one is student assignment
//
// Assert that the assumptions are the same, that the step(goal) appears at the top level of the
// student assignment and that the goal is valid in the student proof all the way to the premises.

fn main() -> Result<(), String> {
    let args: Vec<_> = env::args().collect();

    if args.len() != 3 {
        return Err(format!("Usage: {} <instructor assignment> <student assignment>", args[0]));
    }

    let instructor_path = Path::new(&args[1]);
    let student_path = Path::new(&args[2]);

    let instructor_file = File::open(&instructor_path).expect("Could not open instructor file");
    let student_file = File::open(&student_path).expect("Could not open student file");

    type P = libaris::proofs::pooledproof::PooledProof<Hlist![Expr]>;
    let (i_prf, i_author, i_hash) = proof_from_xml::<P, _>(&instructor_file).unwrap();
    let (s_prf, s_author, s_hash) = proof_from_xml::<P, _>(&student_file).unwrap();

    let instructor_premises = i_prf.premises();
    let student_premises = s_prf.premises();

    // Adds the premises into two sets to compare them
    let instructor_set = instructor_premises.into_iter().map(|r| i_prf.lookup_expr(r)).collect::<Option<HashSet<Expr>>>().expect("Instructor set creation failed");
    let student_set = student_premises.into_iter().map(|r| s_prf.lookup_expr(r)).collect::<Option<HashSet<Expr>>>().expect("Student set creation failed");

    if instructor_set != student_set {
        return Err("Premises do not match!".into());
    }

    // Gets the top level lines (goals)
    let instructor_lines = i_prf.direct_lines();
    let student_lines = s_prf.direct_lines();

    // TODO: Verify that the goals are in the student lines and that the instructor's conclusion line matches some student's conclusion, and that the student's conclusion checks out using BFS.
    // Check that each line is in the student lines
    //for line in instructor_lines {
    //    
    //}

    return Ok(());
}

