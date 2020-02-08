DATOMIC_VERSION       := datomic-pro-0.9.5783
TRANSACTOR_PROPERTIES := ~/datomic/$(DATOMIC_VERSION)/transactor.properties
DATOMIC_URL           := datomic:dev://localhost:4334/fulcro_rad_example

repl:
	DATOMIC_URL=$(DATOMIC_URL) clj -A:nrepl -A:dev -m nrepl.cmdline --middleware '["refactor-nrepl.middleware/wrap-refactor", "cider.nrepl/cider-middleware"]'

transactor:
	~/datomic/$(DATOMIC_VERSION)/bin/transactor $(TRANSACTOR_PROPERTIES)

cljs:
	shadow-cljs watch example

docs/DevelopersGuide.html: docs/DevelopersGuide.adoc
	asciidoctor -o docs/DevelopersGuide.html -b html5 -r asciidoctor-diagram docs/DevelopersGuide.adoc

book: docs/DevelopersGuide.html

publish: book
	rsync -av docs/DevelopersGuide.html linode:/usr/share/nginx/html/RAD.html
