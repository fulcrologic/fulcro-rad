DATOMIC_VERSION       := datomic-pro-0.9.5783
TRANSACTOR_PROPERTIES := ~/datomic/$(DATOMIC_VERSION)/transactor.properties
DATOMIC_URL           := datomic:dev://localhost:4334/fulcro_rad_example

repl:
	clj -A:nrepl -A:dev -m nrepl.cmdline --middleware '["refactor-nrepl.middleware/wrap-refactor", "cider.nrepl/cider-middleware"]'

transactor:
	~/datomic/$(DATOMIC_VERSION)/bin/transactor $(TRANSACTOR_PROPERTIES)

cljs:
	shadow-cljs watch example
