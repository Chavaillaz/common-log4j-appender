# Same as -> export newVersion=x.x.x
# Stops at the first command failing, rather than tagging a version that could not be set
set -eu

# The release commit is undone at the end, which would discard any uncommitted change along with it
if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "Commit or stash the changes first" >&2
    exit 1
fi

echo "Enter version number (x.x.x): "
read -r newVersion
case "${newVersion}" in
    "" | *[!0-9.]* | .* | *. | *..*)
        echo "Invalid version number: ${newVersion}" >&2
        exit 1
        ;;
esac

export GPG_TTY=$(tty)
mvn versions:set -DnewVersion="${newVersion}" -DgenerateBackupPoms=false
# The dependency the README shows names the version released, checked before anything is tagged
perl -0pi -e 's{(<artifactId>common-log4j-appender</artifactId>\s*<version>)[^<]*(</version>)}{${1}'"${newVersion}"'${2}}' README.md
if ! grep -qF "<version>${newVersion}</version>" README.md; then
    echo "README.md shows no dependency on common-log4j-appender to give the version" >&2
    git checkout pom.xml README.md
    exit 1
fi
# mvn clean deploy -Prelease -> Done by Github actions
# Only the version change goes in the release, not whatever else lies untracked in the working tree
git add pom.xml README.md
git commit -m "Release ${newVersion}"
git tag "${newVersion}"
git push origin "${newVersion}"
git reset HEAD~1
git checkout pom.xml
# Master keeps its snapshot version, and its README names the version released, unless it did already
if ! git diff --quiet README.md; then
    git commit -m "Name version ${newVersion} in the README" README.md
fi
