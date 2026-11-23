#import <UIKit/UIKit.h>
#import <TargetConditionals.h>

// Shared iOS / iPadOS / tvOS UIKit entry point. Transport binding is tracked
// separately; this deliberately NEVER pretends to connect to a PS5.
@interface RelayController : UIViewController
@property(nonatomic,strong) UILabel *heading;
@property(nonatomic,strong) UILabel *message;
@property(nonatomic,strong) UISegmentedControl *tabs;
@end

@implementation RelayController
- (void)loadView {
    UIView *view = [[UIView alloc] initWithFrame:[[UIScreen mainScreen] bounds]];
    view.backgroundColor = [UIColor colorWithRed:.035 green:.055 blue:.18 alpha:1];
    self.view=view;
}
- (void)viewDidLoad {
    [super viewDidLoad];
    UILabel *brand=[[UILabel alloc] init];
    brand.translatesAutoresizingMaskIntoConstraints=NO;
    brand.text=@"RELAY";
    brand.textColor=[UIColor whiteColor];
    brand.font=[UIFont boldSystemFontOfSize:24];
    [self.view addSubview:brand];
    self.heading=[[UILabel alloc] init];
    self.heading.translatesAutoresizingMaskIntoConstraints=NO;
    self.heading.text=@"Connect to your PS5";
    self.heading.textAlignment=NSTextAlignmentCenter;
    self.heading.textColor=[UIColor whiteColor];
    self.heading.font=[UIFont systemFontOfSize:32];
    [self.view addSubview:self.heading];
    self.message=[[UILabel alloc] init];
    self.message.translatesAutoresizingMaskIntoConstraints=NO;
    self.message.text=@"Apple platform UI shell — streaming integration in progress";
    self.message.textAlignment=NSTextAlignmentCenter;
    self.message.numberOfLines=0;
    self.message.textColor=[UIColor colorWithRed:.65 green:.74 blue:.94 alpha:1];
    [self.view addSubview:self.message];
    UIButton *connect=[UIButton buttonWithType:UIButtonTypeSystem];
    connect.translatesAutoresizingMaskIntoConstraints=NO;
    [connect setTitle:@"Connect" forState:UIControlStateNormal];
    [connect setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
    connect.backgroundColor=[UIColor colorWithRed:.18 green:.32 blue:.72 alpha:1];
    connect.layer.cornerRadius=16;
    [connect addTarget:self action:@selector(connectPressed) forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:connect];
    self.tabs=[[UISegmentedControl alloc] initWithItems:@[@"Remote Play",@"Cloud",@"Search",@"Settings"]];
    self.tabs.translatesAutoresizingMaskIntoConstraints=NO;
    self.tabs.selectedSegmentIndex=0;
    [self.tabs addTarget:self action:@selector(tabChanged) forControlEvents:UIControlEventValueChanged];
    [self.view addSubview:self.tabs];
    NSDictionary *views=@{@"brand":brand,@"heading":self.heading,@"message":self.message,@"connect":connect,@"tabs":self.tabs};
    [self.view addConstraints:[NSLayoutConstraint constraintsWithVisualFormat:@"H:|-32-[brand]" options:0 metrics:nil views:views]];
    [self.view addConstraints:[NSLayoutConstraint constraintsWithVisualFormat:@"V:|-58-[brand]" options:0 metrics:nil views:views]];
    for (UIView *v in @[self.heading,self.message,connect,self.tabs]) {
        [self.view addConstraint:[NSLayoutConstraint constraintWithItem:v attribute:NSLayoutAttributeCenterX relatedBy:NSLayoutRelationEqual toItem:self.view attribute:NSLayoutAttributeCenterX multiplier:1 constant:0]];
        [self.view addConstraint:[NSLayoutConstraint constraintWithItem:v attribute:NSLayoutAttributeWidth relatedBy:NSLayoutRelationLessThanOrEqual toItem:self.view attribute:NSLayoutAttributeWidth multiplier:.94 constant:0]];
    }
    [self.view addConstraints:[NSLayoutConstraint constraintsWithVisualFormat:@"V:[heading]-18-[message]-30-[connect(52)]-66-[tabs(54)]" options:0 metrics:nil views:views]];
    [self.view addConstraint:[NSLayoutConstraint constraintWithItem:self.heading attribute:NSLayoutAttributeCenterY relatedBy:NSLayoutRelationEqual toItem:self.view attribute:NSLayoutAttributeCenterY multiplier:1 constant:-140]];
    [self.view addConstraints:[NSLayoutConstraint constraintsWithVisualFormat:@"H:[connect(200)]" options:0 metrics:nil views:views]];
    [self.view addConstraints:[NSLayoutConstraint constraintsWithVisualFormat:@"H:[tabs(340)]" options:0 metrics:nil views:views]];
}
- (void)connectPressed {
    self.message.text=@"Native PS4/PS5 transport, account pairing and VideoToolbox decoding have not yet been ported. No connection attempted.";
}
- (void)tabChanged {
    NSArray *names=@[@"Connect to your PS5",@"Cloud Streaming",@"Search",@"Settings"];
    self.heading.text=[names objectAtIndex:self.tabs.selectedSegmentIndex];
}
@end

@interface RelayAppDelegate : UIResponder <UIApplicationDelegate>
@property(nonatomic,strong) UIWindow *window;
@end
@implementation RelayAppDelegate
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)options {
    (void)application; (void)options;
    self.window=[[UIWindow alloc] initWithFrame:[[UIScreen mainScreen] bounds]];
    self.window.rootViewController=[[RelayController alloc] init];
    [self.window makeKeyAndVisible];
    return YES;
}
@end
int main(int argc,char *argv[]) {
    @autoreleasepool { return UIApplicationMain(argc,argv,nil,NSStringFromClass([RelayAppDelegate class])); }
}
