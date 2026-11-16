#!/usr/bin/env fish
#
# Publish 8BitDo Controller Support to Modrinth.
#
#   tools/publish-modrinth.fish              create the project (if needed), upload
#                                            the icon and the version jar
#   tools/publish-modrinth.fish --dry-run    build and print the payloads, no network
#
# Token: ~/.config/modrinth/token, a single line holding a personal access token
# with the scopes USER_READ, PROJECT_CREATE, PROJECT_WRITE, VERSION_CREATE and
# VERSION_WRITE (see modrinth/README.md). The token is read into a variable and
# only passed to curl in an Authorization header; it is never printed.
#
# Environment:
#   MODRINTH_PROJECT_ID   existing project id or slug; when set, the project is
#                         not created and this id is used instead.
#
# Payload files (all in modrinth/): project.json, description.md, version.json,
# changelog.md. The icon is docs/logo/icon.png, the jar is the "file" entry of
# version.json (relative to the repository root).
#
# Nothing is published while --dry-run is given; that mode needs no token.

set -l root (path resolve (status dirname)/..)
set -l api https://api.modrinth.com/v2
set -l project_json $root/modrinth/project.json
set -l version_json $root/modrinth/version.json
set -l description $root/modrinth/description.md
set -l changelog $root/modrinth/changelog.md
set -l icon $root/docs/logo/icon.png

set -l dry_run false
for arg in $argv
    switch $arg
        case --dry-run
            set dry_run true
        case -h --help
            printf '%s\n' "usage: tools/publish-modrinth.fish [--dry-run]" \
                "" \
                "Publishes modrinth/project.json + description.md, docs/logo/icon.png" \
                "and the jar named in modrinth/version.json. Reads the token from" \
                "~/.config/modrinth/token and never prints it." \
                "" \
                "MODRINTH_PROJECT_ID=<id> skips project creation."
            exit 0
        case '*'
            echo "publish: unknown argument: $arg" >&2
            exit 64
    end
end

function __fail --argument-names message
    echo "publish: error: $message" >&2
    exit 1
end

for file in $project_json $version_json $description $changelog $icon
    test -f $file; or __fail "missing $file"
end

set -l jar (jq -r '.file' $version_json)
string match -q '/*' -- $jar; or set jar $root/$jar
test -f $jar; or __fail "missing jar $jar (run ./gradlew build first)"

set -l icon_size (stat -c '%s' $icon)
test $icon_size -le 262144; or __fail "icon is $icon_size bytes, Modrinth allows at most 256 KiB"

set -g __publish_tmp (mktemp -d)
set -l tmp $__publish_tmp
function __cleanup --on-event fish_exit
    command rm -rf -- $__publish_tmp
end

# ---- project payload -------------------------------------------------------

jq --rawfile body $description 'del(.body_file) | .body = $body' $project_json > $tmp/project.json
or __fail "could not build the project payload"

set -l project_id $MODRINTH_PROJECT_ID

# ---- token -----------------------------------------------------------------

set -l token ""
if test -z "$project_id"; or not $dry_run
    set -l token_file $HOME/.config/modrinth/token
    if not $dry_run
        test -f $token_file; or __fail "no token at $token_file (see modrinth/README.md)"
        set -l perms (stat -c '%a' $token_file)
        test "$perms" = "600"; or echo "publish: warning: $token_file is mode $perms, chmod 600 recommended" >&2
        set token (string trim < $token_file)
        test -n "$token"; or __fail "$token_file is empty"
        # Keep the token out of the process list: curl reads the header from a file.
        printf 'Authorization: %s\n' "$token" > $tmp/auth
        chmod 600 $tmp/auth
    end
end

# ---- validate categories against the live tag list -------------------------

if not $dry_run
    set -l known_cats (curl -sS -m 20 "$api/tag/category" | jq -r '.[] | select(.project_type == "mod") | .name')
    test -n "$known_cats"; or __fail "could not read the Modrinth category list"
    set -l kept
    for category in (jq -r '.categories[]' $tmp/project.json)
        if contains -- $category $known_cats
            set -a kept $category
        else
            echo "publish: warning: dropping unknown Modrinth category '$category'" >&2
        end
    end
    test (count $kept) -gt 0; or __fail "no valid categories in modrinth/project.json"
    set -l kept_json (jq -cn --args '$ARGS.positional' -- $kept)
    jq --argjson cats "$kept_json" '.categories = $cats' $tmp/project.json > $tmp/project.validated.json
    or __fail "could not validate the categories"
    command mv -- $tmp/project.validated.json $tmp/project.json
end

if $dry_run
    echo "publish: dry run, nothing is sent"
    echo "publish: project payload:"
    jq . $tmp/project.json
    echo "publish: icon: $icon ($icon_size bytes)"
    echo "publish: version payload:"
    set -l dry_project (test -n "$project_id"; and echo $project_id; or echo "<new project id>")
    jq --rawfile changelog $changelog \
        --arg project_id "$dry_project" \
        --arg file_name (path basename $jar) \
        'del(.changelog_file, .file)
         | .changelog = $changelog
         | .project_id = $project_id
         | .file_parts = [$file_name]
         | .primary_file = $file_name' \
        $version_json
    echo "publish: jar: $jar"
    exit 0
end

# ---- create the project ----------------------------------------------------

if test -z "$project_id"
    echo "publish: creating project from modrinth/project.json"
    set -l response (curl -sS -X POST "$api/project" \
        -H "@$tmp/auth" \
        -F "data=@$tmp/project.json;type=application/json" \
        -w '\n%{http_code}')
    set -l code $response[-1]
    set -l body (string join "\n" $response[1..-2])
    if test "$code" != "200"
        echo "publish: project creation failed (HTTP $code)" >&2
        echo "$body" >&2
        exit 1
    end
    set project_id (echo "$body" | jq -r '.id')
    test -n "$project_id" -a "$project_id" != "null"; or __fail "Modrinth returned no project id"
    echo "publish: project id $project_id"
    echo "publish: set MODRINTH_PROJECT_ID=$project_id for future runs"
else
    echo "publish: using existing project '$project_id'"
end

# ---- icon ------------------------------------------------------------------

echo "publish: uploading icon"
set -l icon_response (curl -sS -X PATCH "$api/project/$project_id/icon?ext=png" \
    -H "@$tmp/auth" \
    -H "Content-Type: image/png" \
    --data-binary "@$icon" \
    -w '\n%{http_code}')
set -l icon_code $icon_response[-1]
if test "$icon_code" != "204"
    echo "publish: icon upload failed (HTTP $icon_code)" >&2
    string join "\n" $icon_response[1..-2] >&2
    exit 1
end

# ---- version ---------------------------------------------------------------

set -l jar_name (path basename $jar)
jq --rawfile changelog $changelog \
    --arg project_id "$project_id" \
    --arg file_name "$jar_name" \
    'del(.changelog_file, .file)
     | .changelog = $changelog
     | .project_id = $project_id
     | .file_parts = [$file_name]
     | .primary_file = $file_name' \
    $version_json > $tmp/version.json
or __fail "could not build the version payload"

echo "publish: uploading version $jar_name"
set -l version_response (curl -sS -X POST "$api/version" \
    -H "@$tmp/auth" \
    -F "data=@$tmp/version.json;type=application/json" \
    -F "$jar_name=@$jar" \
    -w '\n%{http_code}')
set -l version_code $version_response[-1]
set -l version_body (string join "\n" $version_response[1..-2])
if test "$version_code" != "200"
    echo "publish: version upload failed (HTTP $version_code)" >&2
    echo "$version_body" >&2
    exit 1
end

set -l version_id (echo "$version_body" | jq -r '.id')
echo "publish: done"
echo "publish: project https://modrinth.com/mod/$project_id"
echo "publish: version $version_id"
