#!/bin/sh
sleep 10
/opt/trellis/bin/trellis-db db migrate /config.yml
/opt/trellis/bin/trellis-db server /config.yml
