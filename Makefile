docs/DevelopersGuide.html: docs/DevelopersGuide.adoc
	asciidoctor -o docs/DevelopersGuide.html -b html5 -r asciidoctor-diagram docs/DevelopersGuide.adoc

book: docs/DevelopersGuide.html

publish: book
	rsync -av docs/DevelopersGuide.html linode:/usr/share/nginx/html/RAD.html
	rsync -av docs/assets linode:/usr/share/nginx/html/
