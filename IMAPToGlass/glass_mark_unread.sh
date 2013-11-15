#!/bin/bash

ITEM=`cat /dev/stdin | tr -cd '[:print:]\n' | grep "itemId" | awk '{ print $2 }' | tr -d '",'`
if [ -n "$ITEM" ]; then
	echo $ITEM >> /tmp/glass_unread
fi


echo "Content-type: text/plain"
echo

