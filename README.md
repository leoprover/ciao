ciao
------
_*C*omprehension *I*nstance *A*ugment*o*r_

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
