package scala.meta.internal.tvp

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.concurrent.TrieMap

import scala.meta.dialects
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.{lsp4j => l}

class MetalsTreeViewProvider(
    getFolderTreeViewProviders: () => List[FolderTreeViewProvider],
    languageClient: MetalsLanguageClient,
    sh: ScheduledExecutorService,
) extends TreeViewProvider {
  private val ticks =
    TrieMap.empty[String, ScheduledFuture[_]]

  override def init(): Unit = {
    languageClient.metalsTreeViewDidChange(
      TreeViewDidChangeParams(
        Array(
          TreeViewNode.empty(Project),
          TreeViewNode.empty(Build),
          TreeViewNode.empty(Compile),
        )
      )
    )
  }

  override def reset(): Unit = getFolderTreeViewProviders().foreach(_.reset())

  def echoCommand(command: BaseCommand, icon: String): TreeViewNode =
    TreeViewNode(
      viewId = "help",
      nodeUri = s"help:${command.id}",
      label = command.title,
      command = MetalsCommand(
        command.title,
        ClientCommands.EchoCommand.id,
        command.description,
        Array(command.id: AnyRef),
      ),
      icon = icon,
      tooltip = command.description,
    )

  override def children(
      params: TreeViewChildrenParams
  ): MetalsTreeViewChildrenResult = {
    val folderTreeViewProviders = getFolderTreeViewProviders()
    val children: Array[TreeViewNode] = params.viewId match {
      case Help =>
        Array(
          echoCommand(ServerCommands.RunDoctor, "bug"),
          echoCommand(ServerCommands.GotoLog, "bug"),
          echoCommand(ServerCommands.ReadVscodeDocumentation, "book"),
          echoCommand(ServerCommands.ReadBloopDocumentation, "book"),
          echoCommand(ServerCommands.ChatOnDiscord, "discord"),
          echoCommand(ServerCommands.OpenIssue, "issue-opened"),
          echoCommand(ServerCommands.OpenFeatureRequest, "github"),
          echoCommand(ServerCommands.MetalsGithub, "github"),
          echoCommand(ServerCommands.BloopGithub, "github"),
          echoCommand(ServerCommands.ScalametaTwitter, "twitter"),
        )
      case Project =>
        val showFolderName = folderTreeViewProviders.length > 1
        folderTreeViewProviders
          .map(_.getProjectRoot(Option(params.nodeUri), showFolderName))
          .flatten
          .toArray
      case Build =>
        Option(params.nodeUri) match {
          case None =>
            Array(
              TreeViewNode.fromCommand(ServerCommands.ImportBuild, "sync"),
              TreeViewNode
                .fromCommand(ServerCommands.NewScalaProject, "empty-window"),
              TreeViewNode
                .fromCommand(ServerCommands.ConnectBuildServer, "connect"),
              TreeViewNode
                .fromCommand(ServerCommands.CascadeCompile, "cascade"),
              TreeViewNode.fromCommand(ServerCommands.CancelCompile, "cancel"),
              TreeViewNode.fromCommand(ServerCommands.CleanCompile, "clean"),
              TreeViewNode
                .fromCommand(ServerCommands.RestartBuildServer, "debug-stop"),
              TreeViewNode
                .fromCommand(
                  ServerCommands.ResetNotifications,
                  "notifications-clear",
                ),
              TreeViewNode
                .fromCommand(
                  ServerCommands.AnalyzeStacktrace,
                  "bug",
                ),
            )
          case _ =>
            Array()
        }
      case Compile =>
        folderTreeViewProviders
          .map(_.getOngoingCompilations(Option(params.nodeUri)))
          .flatten
          .toArray
      case _ => Array.empty
    }
    MetalsTreeViewChildrenResult(children)
  }
  override def reveal(
      path: AbsolutePath,
      pos: l.Position,
  ): Option[TreeViewNodeRevealResult] = {
    val input = path.toInput
    val occurrences =
      Mtags
        .allToplevels(
          input,
          // TreeViewProvider doesn't work with Scala 3 - see #2859
          dialects.Scala213,
        )
        .occurrences
        .filterNot(_.symbol.isPackage)
    if (occurrences.isEmpty) None
    else {
      val closestSymbol = occurrences.minBy { occ =>
        val startLine = occ.range.fold(Int.MaxValue)(_.startLine)
        val distance = math.abs(pos.getLine - startLine)
        val isLeading = pos.getLine() > startLine
        (!isLeading, distance)
      }
      val result =
        getFolderTreeViewProviders().foldLeft(Option.empty[List[String]]) {
          case (None, treeViewProvider) =>
            treeViewProvider.revealResult(path, closestSymbol)
          case (Some(value), _) => Some(value)
        }
      result.map { uriChain =>
        uriChain.foreach { uri =>
          // Cache results
          children(TreeViewChildrenParams(Project, uri))
        }
        TreeViewNodeRevealResult(Project, uriChain.toArray)
      }
    }
  }

  override def onCollapseDidChange(
      params: TreeViewNodeCollapseDidChangeParams
  ): Unit = getFolderTreeViewProviders().foreach(_.onCollapseDidChange(params))

  override def parent(
      params: TreeViewParentParams
  ): TreeViewParentResult =
    params.viewId match {
      case Project =>
        getFolderTreeViewProviders()
          .map(_.parent(params.nodeUri))
          .collectFirst { case Some(value) =>
            value
          }
          .getOrElse(TreeViewParentResult(null))
      case _ => TreeViewParentResult(null)
    }

  override def onVisibilityDidChange(
      params: TreeViewVisibilityDidChangeParams
  ): Unit = {
    val trees = getFolderTreeViewProviders()
    if (params.visible) {
      params.viewId match {
        case TreeViewProvider.Compile =>
          ticks(params.viewId) = sh.scheduleAtFixedRate(
            () => {
              val all = trees.map(_.tickBuildTreeView()).collect {
                case Some(value) => value
              }
              if (all.nonEmpty) {
                languageClient.metalsTreeViewDidChange(
                  TreeViewDidChangeParams(all.flatten.toArray)
                )
              }
            },
            1,
            1,
            TimeUnit.SECONDS,
          )
        case TreeViewProvider.Project =>
          val toUpdate = trees.map(_.flushPendingProjectUpdates).collect {
            case Some(value) => value
          }
          if (toUpdate.nonEmpty) {
            languageClient.metalsTreeViewDidChange(
              TreeViewDidChangeParams(toUpdate.flatten.toArray)
            )
          }
        case _ =>
      }
    } else {
      ticks.remove(params.viewId).foreach(_.cancel(false))
    }
  }

  override def onBuildTargetDidCompile(
      id: BuildTargetIdentifier
  ): Unit = {
    val toUpdate =
      getFolderTreeViewProviders().map(_.onBuildTargetDidCompile(id)).collect {
        case Some(value) => value
      }
    if (toUpdate.nonEmpty) {
      languageClient.metalsTreeViewDidChange(
        TreeViewDidChangeParams(toUpdate.flatten.toArray)
      )
    }
  }
}

class FolderTreeViewProvider(
    folder: Folder,
    buildTargets: BuildTargets,
    compilations: () => TreeViewCompilations,
    definitionIndex: GlobalSymbolIndex,
    doCompile: BuildTargetIdentifier => Unit,
    isBloop: () => Boolean,
    statistics: StatisticsConfig,
) {
  private val classpath = new ClasspathSymbols(
    isStatisticsEnabled = statistics.isTreeView
  )
  private val isVisible = TrieMap.empty[String, Boolean].withDefaultValue(false)
  private val isCollapsed = TrieMap.empty[BuildTargetIdentifier, Boolean]
  private val pendingProjectUpdates =
    ConcurrentHashSet.empty[BuildTargetIdentifier]
  val libraries = new ClasspathTreeView[AbsolutePath, AbsolutePath](
    definitionIndex,
    TreeViewProvider.Project,
    s"libraries",
    s"Libraries",
    folder,
    identity,
    _.toURI.toString(),
    _.toAbsolutePath,
    _.filename,
    _.toString,
    () => buildTargets.allWorkspaceJars,
    (path, symbol) => classpath.symbols(path, symbol),
  )

  val projects = new ClasspathTreeView[BuildTarget, BuildTargetIdentifier](
    definitionIndex,
    TreeViewProvider.Project,
    s"projects",
    s"Projects",
    folder,
    _.getId(),
    _.getUri(),
    uri => new BuildTargetIdentifier(uri),
    _.getDisplayName(),
    _.baseDirectory,
    { () =>
      buildTargets.all.filter(target =>
        buildTargets.buildTargetSources(target.getId()).nonEmpty
      )
    },
    { (id, symbol) =>
      if (isBloop()) doCompile(id)
      buildTargets
        .targetClassDirectories(id)
        .flatMap(cd => classpath.symbols(cd.toAbsolutePath, symbol))
        .iterator
    },
  )

  def flushPendingProjectUpdates(): Option[Array[TreeViewNode]] = {
    val toUpdate = pendingProjectUpdates.asScala.iterator
      .filter { id =>
        !isCollapsed.getOrElse(id, true) &&
        isVisible(TreeViewProvider.Project)
      }
      .flatMap(buildTargets.info)
      .toArray
    if (toUpdate.nonEmpty) {
      val nodes = toUpdate.map { target =>
        projects
          .toViewNode(target)
          .copy(collapseState = MetalsTreeItemCollapseState.expanded)
      }
      Some(nodes)
    } else {
      None
    }
  }

  def onBuildTargetDidCompile(
      id: BuildTargetIdentifier
  ): Option[Array[TreeViewNode]] = {
    buildTargets
      .targetClassDirectories(id)
      .foreach(cd => classpath.clearCache(cd.toAbsolutePath))
    if (isCollapsed.contains(id)) {
      pendingProjectUpdates.add(id)
      flushPendingProjectUpdates()
    } else {
      None // do nothing if the user never expanded the tree view node.
    }
  }

  def onCollapseDidChange(
      params: TreeViewNodeCollapseDidChangeParams
  ): Unit = {
    if (projects.matches(params.nodeUri)) {
      val uri = projects.fromUri(params.nodeUri)
      if (uri.isRoot) {
        isCollapsed(uri.key) = params.collapsed
      }
    }
  }

  def parent(
      uri: String
  ): Option[TreeViewParentResult] =
    if (libraries.matches(uri)) {
      Some(TreeViewParentResult(libraries.parent(uri).orNull))
    } else if (projects.matches(uri)) {
      Some(TreeViewParentResult(projects.parent(uri).orNull))
    } else {
      None
    }

  def getOngoingCompilations(nodeUri: Option[String]): Array[TreeViewNode] =
    nodeUri match {
      case None =>
        ongoingCompilations
      case Some(uri) =>
        if (uri == ongoingCompilationNode.nodeUri) {
          ongoingCompilations
        } else {
          ongoingCompileNode(new BuildTargetIdentifier(uri)).toArray
        }
    }

  def getProjectRoot(
      nodeUri: Option[String],
      showFolderName: Boolean,
  ): Array[TreeViewNode] =
    nodeUri match {
      case None if buildTargets.all.nonEmpty =>
        Array(
          projects.root(showFolderName),
          libraries.root(showFolderName),
        )
      case Some(uri) =>
        if (libraries.matches(uri)) {
          libraries.children(uri)
        } else if (projects.matches(uri)) {
          projects.children(uri)
        } else {
          Array.empty
        }
      case _ => Array.empty
    }

  def revealResult(
      path: AbsolutePath,
      closestSymbol: SymbolOccurrence,
  ): Option[List[String]] =
    if (path.isDependencySource(folder.path) || path.isJarFileSystem) {
      buildTargets
        .inferBuildTarget(List(Symbol(closestSymbol.symbol).toplevel))
        .map { inferred =>
          libraries.toUri(inferred.jar, inferred.symbol).parentChain
        }
    } else {
      buildTargets
        .inverseSources(path)
        .map(id => projects.toUri(id, closestSymbol.symbol).parentChain)
    }

  private def ongoingCompilations: Array[TreeViewNode] = {
    compilations().buildTargets.flatMap(ongoingCompileNode).toArray
  }

  private def ongoingCompileNode(
      id: BuildTargetIdentifier
  ): Option[TreeViewNode] = {
    for {
      compilation <- compilations().get(id)
      info <- buildTargets.info(id)
    } yield TreeViewNode(
      TreeViewProvider.Compile,
      id.getUri,
      s"${info.getDisplayName()} - ${compilation.timer.toStringSeconds} (${compilation.progressPercentage}%)",
      icon = "compile",
    )
  }

  private def ongoingCompilationNode: TreeViewNode = {
    TreeViewNode(
      TreeViewProvider.Compile,
      null,
      TreeViewProvider.Compile,
    )
  }

  private def toplevelTreeNodes: Array[TreeViewNode] =
    Array(ongoingCompilationNode)

  private val wasEmpty = new AtomicBoolean(true)
  private val isEmpty = new AtomicBoolean(true)
  def tickBuildTreeView(): Option[Array[TreeViewNode]] = {
    isEmpty.set(compilations().isEmpty)
    val result = if (wasEmpty.get() && isEmpty.get()) {
      None // Nothing changed since the last notification.
    } else {
      Some(toplevelTreeNodes)
    }
    wasEmpty.set(isEmpty.get())
    result
  }

  def reset(): Unit = {
    classpath.reset()
  }
}
