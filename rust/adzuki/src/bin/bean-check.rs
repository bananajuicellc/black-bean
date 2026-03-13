use std::env;
use std::fs;
use std::process;
use adzuki::plugin::process_markdown_stream;
use adzuki::lexer::lex_beancount;
use adzuki::beancount_parser::parse_beancount;

fn get_line_and_col(source: &str, byte_offset: usize) -> (usize, usize) {
    let mut line = 1;
    let mut col = 1;
    for (byte_idx, c) in source.char_indices() {
        if byte_idx == byte_offset {
            break;
        }
        if c == '\n' {
            line += 1;
            col = 1;
        } else {
            col += 1;
        }
    }
    (line, col)
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() != 2 {
        eprintln!("Usage: bean-check <filepath>");
        process::exit(1);
    }

    let filepath = &args[1];
    let source = fs::read_to_string(filepath).unwrap_or_else(|err| {
        eprintln!("Error reading file {}: {}", filepath, err);
        process::exit(1);
    });

    let processed_source = process_markdown_stream(filepath, &source);
    let tokens = lex_beancount(&processed_source);
    let (_, errors) = parse_beancount(&processed_source, &tokens);

    if !errors.is_empty() {
        for error in &errors {
            let (line, col) = get_line_and_col(&processed_source, error.span.start);
            eprintln!("{}:{}:{}: {}", filepath, line, col, error.message);
        }
        process::exit(1);
    }
}
