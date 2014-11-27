# default app to deploy to
APP=snowplow-stream-collector

deploy_clean:
	rm -rf ./.deploy

set_vars:
	# set variables for commit message
	$(eval CURR_DEPLOYER=$(shell whoami;echo $))
	$(eval CURR_HASH=$(shell git rev-parse HEAD;echo $))
	$(eval CURR_AUTHOR=$(shell git --no-pager show -s --format='%an <%ae>';echo $))

# pushes current local changes to heroku
# ex: make APP=snowplow-stream-collector deploy
deploy: deploy_clean set_vars
	# make directory and copy data
	mkdir -p ./.deploy
	cp -r ./2-collectors/scala-stream-collector/* ./.deploy/
	cp ./2-collectors/scala-stream-collector/.gitignore ./.deploy/

	# make git repo
	git --git-dir ./.deploy/.git --work-tree ./.deploy/ init
	git --git-dir ./.deploy/.git --work-tree ./.deploy/ add --a
	git --git-dir ./.deploy/.git --work-tree ./.deploy/ commit -m '${CURR_DEPLOYER} deployed: ${CURR_HASH} by ${CURR_AUTHOR}'
	# push git repo
	git --git-dir ./.deploy/.git --work-tree ./.deploy push git@heroku.com:${APP}.git master --force
