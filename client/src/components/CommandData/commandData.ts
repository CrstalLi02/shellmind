/**
 * SSH always use command category data
 */

export interface CommandItem {
  command: string
  description: string
}

export interface CommandCategory {
  name: string
  emoji: string
  commands: CommandItem[]
}

export const COMMAND_CATEGORIES: CommandCategory[] = [
  {
    name: 'Files and directories',
    emoji: '📁',
    commands: [
      { command: 'ls -la', description: 'List all files including hidden, with details' },
      { command: 'pwd', description: 'Print the current working directory' },
      { command: 'cd /home', description: 'Change to /home' },
      { command: 'mkdir test', description: 'Create a directory named test' },
      { command: 'rmdir test', description: 'Remove empty directory test' },
      { command: 'cp source dest', description: 'Copy file from source to dest' },
      { command: 'mv old new', description: 'Move or rename file old to new' },
      { command: 'rm -rf test', description: 'Force-delete directory test recursively (destructive)' },
      { command: 'find / -name "*.log"', description: 'Find all .log files starting from /' },
      { command: 'locate filename', description: 'Quick file-name lookup (locate database)' },
    ],
  },
  {
    name: 'File contents',
    emoji: '📄',
    commands: [
      { command: 'cat filename', description: 'Print the entire file' },
      { command: 'less filename', description: 'Page through a file (forward and back)' },
      { command: 'head -n 20 filename', description: 'Show the first 20 lines' },
      { command: 'tail -f filename', description: 'Follow new lines at the end of a file' },
      { command: 'grep "pattern" filename', description: 'Search a file for matching lines' },
      { command: 'wc -l filename', description: 'Count lines in a file' },
      { command: 'sort filename', description: 'Sort file contents' },
      { command: 'uniq filename', description: 'Drop consecutive duplicate lines' },
    ],
  },
  {
    name: 'System info',
    emoji: '⚙️',
    commands: [
      { command: 'ps aux', description: 'List all running processes with details' },
      { command: 'top', description: 'Live view of processes and resource usage' },
      { command: 'htop', description: 'Enhanced top with a friendlier UI' },
      { command: 'df -h', description: 'Show disk usage in human-readable form' },
      { command: 'free -m', description: 'Show memory usage in MB' },
      { command: 'uptime', description: 'Show uptime and load average' },
      { command: 'uname -a', description: 'Show kernel information' },
      { command: 'lscpu', description: 'Show CPU architecture' },
      { command: 'lsblk', description: 'List block devices' },
    ],
  },
  {
    name: 'Users and permissions',
    emoji: '👤',
    commands: [
      { command: 'whoami', description: 'Show the current username' },
      { command: 'id', description: 'Show current UID, GID, and groups' },
      { command: 'who', description: 'Show logged-in users' },
      { command: 'w', description: 'Show who is logged in and what they are doing' },
      { command: 'chmod 755 filename', description: 'Set file mode to 755 (rwxr-xr-x)' },
      { command: 'chmod +x script.sh', description: 'Make a script executable' },
      { command: 'chmod 644 filename', description: 'Set file mode to 644 (rw-r--r--)' },
      { command: 'chmod -R 755 dir/', description: 'Recursively chmod a directory and its contents' },
      { command: 'chown user:group file', description: 'Change file owner and group' },
      { command: 'su - username', description: 'Switch user with a login environment' },
      { command: 'sudo command', description: 'Run a command as superuser' },
    ],
  },
  {
    name: 'Network and services',
    emoji: '🌐',
    commands: [
      { command: 'ping -c 4 host', description: 'Send 4 ICMP packets to test connectivity' },
      { command: 'wget url', description: 'Download the file at a URL' },
      { command: 'curl -s url', description: 'Fetch a URL silently' },
      { command: 'netstat -tuln', description: 'Show all listening TCP/UDP ports' },
      { command: 'ss -tuln', description: 'Faster netstat alternative for network status' },
      { command: 'systemctl status service', description: 'Show systemd service status' },
      { command: 'systemctl start service', description: 'Start a systemd service' },
      { command: 'systemctl restart service', description: 'Restart a systemd service' },
      { command: 'iptables -L', description: 'List firewall rules' },
    ],
  },
  {
    name: 'System admin',
    emoji: '🔧',
    commands: [
      { command: 'yum update', description: 'Update all installed packages (RHEL/CentOS)' },
      { command: 'yum install package', description: 'Install a package' },
      { command: 'rpm -qa | grep package', description: 'Query installed RPM packages' },
      { command: 'crontab -l', description: "List this user's crontab" },
      { command: 'crontab -e', description: "Edit this user's crontab" },
      { command: 'mount', description: 'List mounted filesystems' },
      { command: 'umount /dev/sda1', description: 'Unmount a filesystem' },
      { command: 'history', description: 'Show command history' },
      { command: 'clear', description: 'Clear' },
    ],
  },
  {
    name: 'Git',
    emoji: '📦',
    commands: [
      { command: 'git clone url', description: 'Clone a remote repository' },
      { command: 'git status', description: 'Show working tree status' },
      { command: 'git add .', description: 'Stage all files' },
      { command: 'git commit -m "message"', description: 'Commit the index to the local repo' },
      { command: 'git push', description: 'Push to remote' },
      { command: 'git pull', description: 'Pull updates from the remote' },
      { command: 'git checkout branch', description: 'Switch to a branch' },
      { command: 'git branch', description: 'List all branches' },
      { command: 'git log --oneline -10', description: 'Show the last 10 commits, one line each' },
      { command: 'git reset --hard commit', description: 'Hard-reset to a commit (destructive)' },
    ],
  },
  {
    name: 'Docker',
    emoji: '🐳',
    commands: [
      { command: 'docker ps', description: 'List running containers' },
      { command: 'docker ps -a', description: 'List all containers including stopped' },
      { command: 'docker images', description: 'List local images' },
      { command: 'docker run -it image /bin/bash', description: 'Run a container interactively' },
      { command: 'docker stop container', description: 'Stop a running container' },
      { command: 'docker start container', description: 'Start a stopped container' },
      { command: 'docker exec -it container /bin/bash', description: 'Exec into a running container' },
      { command: 'docker build -t tag .', description: 'Build a Docker image' },
      { command: 'docker rm container', description: 'Remove container' },
      { command: 'docker rmi image', description: 'Remove image' },
    ],
  },
  {
    name: 'Maven',
    emoji: '☕',
    commands: [
      { command: 'mvn clean install', description: 'Clean and install the project into the local repo' },
      { command: 'mvn clean compile', description: 'Clean and compile' },
      { command: 'mvn test', description: 'Run tests' },
      { command: 'mvn package', description: 'Package project' },
      { command: 'mvn clean', description: 'Clean build artifacts' },
      { command: 'mvn dependency:tree', description: 'Show dependency tree' },
      { command: 'mvn spring-boot:run', description: 'Run the Spring Boot app' },
      { command: 'mvn archetype:generate', description: 'Generate a project from an archetype' },
    ],
  },
  {
    name: 'Other',
    emoji: '🔍',
    commands: [
      { command: 'date', description: 'Show the current date and time' },
      { command: 'cal', description: "Show this month's calendar" },
      { command: 'cal 2026', description: 'Show the 2026 calendar' },
      { command: 'which command', description: 'Show the executable path of a command' },
      { command: 'whereis command', description: 'Locate binary, source, and man page for a command' },
      { command: 'man command', description: 'Open the man page' },
      { command: 'command --help', description: 'Show command help' },
      { command: 'exit', description: 'Exit the current shell' },
    ],
  },
]

/**
 * get all command flat spread list
 */
export function getAllCommands(): CommandItem[] {
  return COMMAND_CATEGORIES.flatMap((cat) => cat.commands)
}