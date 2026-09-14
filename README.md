ciao
------
_*C*omprehension *I*nstance *A*ugment*o*r_

`ciao` may be referenced to as [![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.20743945.svg)](https://doi.org/10.5281/zenodo.20743945).


## Installation

You need to have `sbt` (scala build tool, https://www.scala-sbt.org) installed on your system.

After unpacking the source distribution, simply run `make`. `sbt` will automatically download all dependencies and generate a runnable JAR, located in `./bin`.

## Usage

```
usage: ciao [options] <problem file>

 <command> is the command to be executed (see below). <problem file> can be
 either a file name or '-' (without quotes) for stdin. If <output file> is
 specified, the result is written to <output file>, otherwise to stdout.

 Options:
  --tstp                   Enable TSTP-compatible output: The output in <output file>
                           (or stdout) will start with a SZS status value and the output
                           will be wrapped within SZS BEGIN and SZS END block delimiters.
                           Disabled by default.
  --output <output file>   Write the result to a file instead of stdout
  --collect                Collect all augmentations in one file.
  --version                Prints the version number of the executable and terminates.
  --help                   Prints this description and terminates.
```
