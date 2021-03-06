#!/usr/bin/env python
from imaplib import IMAP4_SSL
import email
from oauth2client.client import OAuth2WebServerFlow
from apiclient.discovery import build
import httplib2
import time
import shutil
import os
import unicodedata

# Define constants
# Google API client_id and client_secret
CLIENT_ID=
CLIENT_SECRET=

# IMAP username/password
IMAP_UN=
IMAP_PW=

# Callback url (ie HTTPS URL for glass_mark_unread.sh)
CALLBACK_URL=

# Log into google...
flow = OAuth2WebServerFlow(client_id=CLIENT_ID,
				client_secret=CLIENT_SECRET,
				scope="https://www.googleapis.com/auth/glass.timeline",
				redirect_uri="http://localhost/oauth2callback",
				access_type='offline', approval_prompt='force')

print("Go to: " + flow.step1_get_authorize_url())
code = raw_input('Code? ')


# Set up credentials and mirror service
creds = flow.step2_exchange(code)
mirror_service = None

def create_service():
	global mirror_service
	http = httplib2.Http()
	http = creds.authorize(http)
	mirror_service = build('mirror', 'v1', http=http)

create_service()

# Register for Subscription callbacks (do this only once)
"""post_body = {
	'callbackUrl': CALLBACK_URL,
	'collection': 'timeline'
}
mirror_service.subscriptions().insert(body=post_body).execute()"""

# Define glass-interaction functions
def post(text):
	body = {
		'notification': {'level': 'DEFAULT'},
		'text': text,
		'menuItems': [ {
			'action': 'DELETE',
		} ]
	}
	return mirror_service.timeline().insert(body=body).execute().get('id')

def remove_item(id):
	mirror_service.timeline().delete(id=id).execute()

def clear_timeline():
	for item in mirror_service.timeline().list(maxResults=50).execute().get('items', []):
		remove_item(item['id'])

# Define some useful IMAP Functions
printable = set(['L', 'N', 'P', 'S', 'Z']) # Letter, Number, Punctuation, Symbol, Separator
def strip_control_chars(s):
	# LTR stops here...
	return ''.join(c for c in unicode(s, errors='ignore') if unicodedata.category(c)[0] in printable)

def format_message(folder, raw_message):
	message = email.message_from_string(unicode(data[0][1], errors='ignore'))
	src, encoding = email.header.decode_header(message['From'])[0]
	subj, encoding = email.header.decode_header(message['Subject'])[0]
	return folder + "\nFrom: " + strip_control_chars(src) + "\nSubj: " + strip_control_chars(subj)

# Set up IMAP
mail = IMAP4_SSL('127.0.0.1')
mail.login(IMAP_UN, IMAP_PW)


# Clear timeline enter main loop
clear_timeline()
message_to_id = {}
id_to_message = {}


while True:
	still_unread = set()
	status, folders = mail.list()
	assert status == "OK"

	# Get the list of messages from each folder
	for f in [e.split('"')[3] for e in folders]:
		status, count = mail.select(f, readonly=True)
		assert status == "OK"
		status, msgnums = mail.uid("SEARCH", None, "UnSeen")
		assert status == "OK"
		for m in msgnums[0].split(" "):
			if m == "":
				continue
			message = f, m
			still_unread.add(message)
			if message in message_to_id:
				continue
			print("Fetching (" + message[0] + ", " + message[1] + ")")
			status, data = mail.uid("FETCH", m, "(RFC822)")
			assert status == "OK"
			id = None
			for i in range(10):
				try:
					id = post(format_message(f, data[0][1]))
					break
				except:
					if i == 9:
						raise
					time.sleep(1)
					create_service()
			message_to_id[message] = id
			id_to_message[id] = message

	# Remove unread items (sync notifications)
	removed = set()
	for m in message_to_id:
		if not m in still_unread:
			print("Removing (" + m[0] + ", " + m[1] + ")")
			for i in range(10):
				try:
					remove_item(message_to_id[m])
					break
				except:
					if i == 9:
						raise
					time.sleep(1)
					create_service()
			removed.add(m)
	for m in removed:
		del id_to_message[message_to_id[m]]
		del message_to_id[m]

	# Get the list of messages we should mark unread
	shutil.move('/tmp/glass_unread', '/tmp/glass_unread_t')
	open('/tmp/glass_unread', 'a').close()
	shutil.copymode('/tmp/glass_unread_t', '/tmp/glass_unread')

	f = open('/tmp/glass_unread_t', 'r')
	l = f.readline()
	while l:
		message = id_to_message.get(l.strip())
		if message:
			print("Marking (" + message[0] + ", " + message[1] + ") unread")
			status, count = mail.select(message[0])
			assert status == "OK"
			status, data = mail.uid("FETCH", message[1], "(RFC822)")
			assert status == "OK"
			del id_to_message[l.strip()]
			del message_to_id[message]

		l = f.readline()
	f.close()
	os.remove('/tmp/glass_unread_t')
	
	# Pound on the IMAP Server, but we're light on Glass calls, so its OK
	time.sleep(1)
