#Thoughts--
#Still need to manually check that the jars are being created in broad artifactory
#Need to manually investigate if tests aren't being written
#Need to manualy update the release portion of Cromwell
#Need to make sure that the changelog.md file doesn't get weirdly altered when merging to master for Cromwell

String broadGithub = "https://github.com/broadinstitute"

task dependencyRelease {
   String repo
   String releaseV

   command {
     set -e
     set -x 
     git clone ${broadGithub}/${repo}.git
     cd ${repo}
     git checkout develop
     git pull --rebase

     currentV=$(grep "git.baseVersion " build.sbt | cut -d "=" -f 2 | tr -d \"\,[:space:])
     
     echo "Version of ${repo} on the develop branch is $currentV"

     if [ "${releaseV}" != "$${currentV}" ]; then
       echo "Updating ${repo} to ${releaseV}"
       sed -i '' "s/git.baseVersion[[:space:]]:=[[:space:]]\"${currentV}\"/git.baseVersion := \"0.23\"/g" build.sbt
       sbt compile
       git add build.sbt
       git commit -m "Update ${repo} version from $currentV to ${releaseV}"
       
       # wdl4s needs a scala docs update
       if [ ${repo} -eq "wdl4s" ]; then
         sbt 'set scalacOptions in (Compile, doc) := List("-skip-packages", "better")' doc
         git checkout gh-pages
         mv target/scala-2.11/api ${releaseV}
         git add ${releaseV}
         git commit -m "API Docs"
         #git push origin gh-pages
         git checkout develop
         sed -i -e 's/'"$currentV"'/'"${releaseV}"'/' README.md
         git add README.md
         git commit -m "Update ${repo} API docs references"
       fi
       
     else
       echo "${repo} is already on version ${releaseV}. Skipping..."
     fi

    #git checkout master
    #git pull --rebase
    #git merge origin/develop
    #git tag ${releaseV}
    #git push origin master
    #git push --tags
   }

   output{
     String updatedVersion = releaseV
   }
}

task wdltoolRelease {
String baseDir
String repo
String releaseV
String wdl4sV

  command {
    cd ${baseDir}/${repo}
        git checkout develop
        git pull

        currentV=`grep "git.baseVersion " build.sbt | cut -d "=" -f2 | tr -d \"\,`
        
        echo "Version of ${repo} on the develop branch is $currentV"

        if [ ${releaseV} -ne $version ];then
           echo "Updating ${repo} to ${releaseV}"
           sed -i -e 's/'"$currentV"'/'"${releaseV}"'/' build.sbt

           #updating the Wdl4s version
           currentWdl4sV=`grep "wdl4s" build.sbt | cut -d "%" -f 4 | tr -d \"\,`
           sed -i -e 's/'"$currentWdl4sV"'/'"${wdl4sV}"'/' build.sbt

           git add build.sbt
           git commit -m "Update ${repo} version from $currentV to ${releaseV}"
           #git push origin develop
        fi

    #git checkout master
    #git merge --squash origin/develop
    #git tag ${releaseV}
    #git push origin master
    #git push --tags
  }
  output {
    String updatedVersion = releaseV
  }

}

task cromwellRelease {
  String repo
  String releaseV
  String baseDir
  String wdl4sV
  String lenthallV

  command {
    cd ${baseDir}/${repo}
    git checkout develop
    git pull

    currentV=`grep "cromwellVersion " project/Version.scala | cut -d "=" -f2 | tr -d \"`
    echo "Version of ${repo} on the develop branch is $version"

    if [ ${releaseV} -ne $version ];then
       echo "Need to update the ${repo} version on the develop branch to ${releaseV}" >&2
       sed -i -e 's/'"$currentV"'/'"${releaseV}"'/' project/Version.scala
       git add project/Version.scala

       #updating the Wdl4s version
       currentWdl4sV=`grep "wdl4sV " project/Dependencies.scala | cut -d "=" -f 2 | tr -d \"\,`
       sed -i -e 's/'"$currentWdl4sV"'/'"${wdl4sV}"'/' project/Dependencies.scala

       #updating the Lenthall version
       currentLenthallV=`grep "lenthallV " project/Dependencies.scala | cut -d "=" -f 2 | tr -d \"\,`
       sed -i -e 's/'"$currentLenthallV"'/'"${lenthallV}"'/' project/Dependencies.scala
       git add project/Dependencies.scala

       #git commit -m "Update ${repo} version from $currentV to ${releaseV} and updated dependencies"
    fi

   #git checkout master
   #git merge --squash origin/develop
   #git tag ${releaseV}
   #git push origin master
   #git push --tags
  }

  output{
    String updatedVersion = releaseV
  }
}

task runTests {
  String stagingArea
  String repo

   command {
      cd ${stagingArea}
      sbt test > results.txt
      testsPassed=`grep "All tests passed." results.txt`

      if [[ -z $passingTests ]]
      then
        echo "Need to fix sbt tests for ${repo}" >&2
        echo "Resuts of Sbt tests are: `cat results.txt`" >&2
        exit 1
      fi
   }
   output {
     Boolean passed = read_boolean("results.txt")
   }
}

task updateDevelopVersion {
  String baseDir
  String repo
  String upcomingV
  String releasedV

  command {
   cd ${baseDir}/${repo}
   git checkout develop

   if [ ${repo} -eq "cromwell ]; then

     sed -i -e 's/'"${releasedV}"'/'"${upcomingV}"'/' project/Version.scala
     git add project/Version.scala

   else

     sed -i -e 's/'"${releasedV}"'/'"${upcomingV}"'/' build.sbt
     git add build.sbt

   fi

   git commit -m "Updating version of upcoming release for ${repo}"
   git push origin develop

  }
  output {}
}

workflow cromwell_release {

  call dependencyRelease { input: repo = "lenthall", releaseV = "0.23" }
  
}
