"""Exercise the real JVM graph classifier and Meta task types without running tasks."""
import sys
sys.dont_write_bytecode = True
import argparse, json, os, pathlib, subprocess

parser = argparse.ArgumentParser()
for name in ('repo-root', 'output-root', 'gradle-home', 'java-home', 'spatial-plugin-jar'):
    parser.add_argument('--' + name, type=pathlib.Path, required=True)
args = parser.parse_args()
root, output = args.repo_root.resolve(), args.output_root.resolve()
if output.exists() or not output.is_relative_to(root / 'target'):
    raise ValueError('Fresh fixture-owned target output required')
if args.spatial_plugin_jar.name != 'spatial-gradle-plugin-impl-0.13.2.jar':
    raise ValueError('Selected Meta Spatial plugin 0.13.2 required')
output.mkdir(parents=True)
harness = output / 'classifier.groovy'
harness.write_text(r'''
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Zip

def policySource = new File(args[0]).getText('UTF-8')
def loader = new GroovyClassLoader(this.class.classLoader)
// Load the actual policy and observation types without evaluating its app setup.
loader.parseClass('import org.gradle.api.*\n' + policySource.substring(0, policySource.indexOf('\ndef componentInventory')))
def policy = loader.loadClass('AndroidJvmTaskClosure')
def project = ProjectBuilder.builder().withProjectDir(new File(args[1], 'project'))
    .withGradleUserHomeDir(new File(args[1], 'gradle-home')).build()
try {
def resolveType = Class.forName('com.meta.spatial.plugin.ResolveComponentXmlsTask')
def generateType = Class.forName('com.meta.spatial.plugin.schematizedcomponents.GenerateSchematizedComponentsTask')
def resolve = project.tasks.register('resolveComponentXmls', resolveType) {
    outputDirectory.set(project.layout.buildDirectory.dir('generated/xmls'))
}
def generate = project.tasks.register('generateSchematizedComponents', generateType) {
    // Same producer-backed directory edge registered by Meta 0.13.2.
    componentXmlsDirectory.set(resolve.flatMap { it.outputDirectory })
    srcOutputDirectory.set(project.layout.buildDirectory.dir('generated/java'))
    resOutputDirectory.set(project.layout.buildDirectory.dir('generated/res'))
}
def compile = project.tasks.register('compileJava', JavaCompile) {
    source(generate.flatMap { it.srcOutputDirectory })
}
def aggregate = project.tasks.register('earlyJvm', DefaultTask) { dependsOn(compile) }
def facts = [:]
def visit
visit = { task ->
    if (!facts.containsKey(task.path)) {
        facts[task.path] = policy.inspect(task)
        (task.taskDependencies.getDependencies(task) + task.finalizedBy.getDependencies(task)).each { visit(it) }
    }
}
visit(aggregate.get())
assert facts[':compileJava'].dependencies.contains(':generateSchematizedComponents')
assert facts[':generateSchematizedComponents'].dependencies.contains(':resolveComponentXmls')
assert facts.size() == 4
def accepted = new LinkedHashMap(facts)
def marker = new File(args[1], 'unexpected-action')
def forbidden = [
    project.tasks.register('nativeCompile', DefaultTask) { doLast { marker.text = 'native' } },
    project.tasks.register('arbitraryExec', Exec) { commandLine('never-executed') },
    project.tasks.register('packageApk', Zip) { archiveExtension.set('apk') },
    project.tasks.register('unrelatedZip', Zip)
]
def refused = []
for (producer in forbidden) {
    for (edge in ['dependency', 'finalizer']) {
        def task = generate.get()
        if (edge == 'dependency') task.dependsOn(producer)
        else task.finalizedBy(producer)
        facts.clear()
        boolean denied = false
        try { visit(aggregate.get()) }
        catch (org.gradle.api.GradleException failure) {
            denied = failure.message.startsWith('component-early-task-graph: ' + producer.get().path + ' type=')
        }
        assert denied: "Required ${edge} refusal absent for ${producer.name}"
        assert !marker.exists(): 'A task action executed during policy inspection'
        refused.add([task: producer.get().path, edge: edge])
        task.setDependsOn([])
        task.setFinalizedBy([])
    }
}
new File(args[1], 'result.json').text = groovy.json.JsonOutput.toJson([
    status: 'passed', accepted: accepted, refused: refused, task_actions_executed: false,
    scope: 'actual-classifier-and-plugin-types; not the complete product AGP graph'
])
println('Meta XML/source/resource producers accepted; native/Exec/APK/ZIP dependencies and finalizers refused; no task actions executed.')
} finally {
    org.gradle.testfixtures.internal.ProjectBuilderImpl.stop(project)
    org.gradle.testfixtures.internal.ProjectBuilderImpl.getGlobalServices().close()
}
''', encoding='utf-8')
classpath = os.pathsep.join(str(path) for path in (
    args.gradle_home / 'lib/*', args.gradle_home / 'lib/plugins/*', args.spatial_plugin_jar))
command = [str(args.java_home / 'bin/java.exe'), '--add-opens=java.base/java.lang=ALL-UNNAMED',
           '-Duser.home=' + str(output / 'user-home'), '-Dorg.gradle.native=false',
           '-cp', classpath, 'groovy.ui.GroovyMain', str(harness),
           str(root / 'tools/gradle/android-component-closure.gradle'), str(output)]
child = subprocess.run(command, capture_output=True, timeout=60)
(output / 'stdout.bin').write_bytes(child.stdout)
(output / 'stderr.bin').write_bytes(child.stderr)
(output / 'process.json').write_text(json.dumps({'argv': command, 'exit_code': child.returncode}, indent=2))
sys.stdout.buffer.write(child.stdout)
sys.stderr.buffer.write(child.stderr)
raise SystemExit(child.returncode)
