DESTDIR ?= $(HOME)/bin
default: all

all: 
		@echo Building ciao ...
		sbt +assembly
		mkdir bin -p
		cp ciao/target/scala-3.9.0/ciao-app.jar bin/.
		cat ./contrib/exec_dummy bin/ciao-app.jar > bin/ciao
		chmod +x bin/ciao

install:
		install -m 0755 -d $(DESTDIR)
		install -m 0755 bin/ciao $(DESTDIR)

clean:
		rm -rf bin/
		rm -rf target/
		rm -rf ciao/target/
		rm -rf ciao-lib/target/
