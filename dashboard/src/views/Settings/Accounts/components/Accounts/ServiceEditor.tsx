import React, {useState} from 'react';

import Button from '@material-ui/core/Button';
import TextField from '@material-ui/core/TextField';
import {NavLink as RouterLink, Redirect, RouteComponentProps, useHistory} from "react-router-dom"

import {makeStyles} from '@material-ui/core/styles';
import {
  Box,
  Card,
  CardContent,
  CardHeader,
  Checkbox,
  Divider,
  FormControlLabel,
  FormGroup,
  Select, Typography
} from '@material-ui/core';
import {
  AccountRole,
  useAccountsListQuery, useAddServiceAccountMutation,
  useAddUserAccountMutation, useChangeServiceAccountMutation,
  useChangeUserAccountMutation, UserAccountProperties, useServiceAccountInfoLazyQuery,
  useServiceProfilesQuery,
  useUserAccountInfoLazyQuery,
  useWhoAmIQuery
} from '../../../../../generated/graphql';
import clsx from 'clsx';
import Alert from "@material-ui/lab/Alert";

const useStyles = makeStyles(theme => ({
  root: {
    padding: theme.spacing(4)
  },
  card: {
    marginTop: 25
  },
  profile: {
    marginBottom: 20
  },
  profileTitle: {
    marginTop: 5,
    height: 25,
    marginRight: 15
  },
  profileSelect: {
    width: '100%',
    height: 25
  },
  controls: {
    marginTop: 25,
    display: 'flex',
    justifyContent: 'flex-end',
    p: 2
  },
  control: {
    marginLeft: '25px',
    textTransform: 'none'
  },
  alert: {
    marginTop: 25
  }
}));

interface AccountRouteParams {
  account?: string
}

interface AccountEditorParams extends RouteComponentProps<AccountRouteParams> {
  fromUrl: string
}

const AccountEditor: React.FC<AccountEditorParams> = props => {
  const whoAmI = useWhoAmIQuery()
  const {data: accountsList} = useAccountsListQuery()
  const [getAccountInfo, accountInfo] = useServiceAccountInfoLazyQuery()

  const classes = useStyles()

  const [account, setAccount] = useState<string>();
  const [name, setName] = useState<string>();
  const [role, setRole] = useState<AccountRole>();

  const [initialized, setInitialized] = useState(false);

  const editAccount = props.match.params.account

  if (!initialized && whoAmI.data) {
    if (editAccount) {
      if (!accountInfo.data && !accountInfo.loading) {
        getAccountInfo({variables: {account: editAccount}})
      }
      if (accountInfo.data) {
        const info = accountInfo.data.serviceAccountsInfo[0]
        if (info) {
          setAccount(info.account)
          setName(info.name)
          setRole(info.role)
        }
        setInitialized(true)
      }
    } else {
      setInitialized(true)
    }
  }

  const [addAccount, { data: addAccountData, error: addAccountError }] =
    useAddServiceAccountMutation({
      onError(err) { console.log(err) }
    })

  const [changeAccount, { data: changeAccountData, error: changeAccountError }] =
    useChangeServiceAccountMutation({
      onError(err) { console.log(err) }
    })

  if (addAccountData || changeAccountData) {
    return <Redirect to={props.fromUrl}/>
  }

  const validate: () => boolean = () => {
    return  !!account && !!name && !!role &&
            (!!editAccount || !doesAccountExist(account))
  }

  const submit = () => {
    if (validate()) {
      if (editAccount) {
          changeAccount({
            variables: {
              account: account!, name: name, role: role!
            }
          })
      } else {
        addAccount({
          variables: { account: account!, name: name!, role: role! }
        })
      }
    }
  }

  const doesAccountExist: (account: string) => boolean = (account) => {
    return accountsList?!!accountsList.accountsList.find(acc => acc == account):false
  }

  const AccountCard = () => {
    return (
      <Card className={classes.card}>
        <CardHeader title={(editAccount?'Edit ':'New User') +
          ' Account' + (editAccount? ` '${account}'`:'')}/>
        <CardContent>
          { !editAccount?
            <TextField
              autoFocus
              fullWidth
              label="Account"
              margin="normal"
              value={account?account:''}
              helperText={!editAccount && account && doesAccountExist(account) ? 'Account already exists': ''}
              error={!account || (!editAccount && doesAccountExist(account))}
              onChange={(e: any) => setAccount(e.target.value)}
              disabled={editAccount !== undefined}
              required
              variant="outlined"
            />:null }
          <TextField
            fullWidth
            label="Name"
            margin="normal"
            value={name?name:''}
            onChange={(e: any) => setName(e.target.value)}
            error={!name}
            required
            variant="outlined"
            autoComplete="off"
          />
        </CardContent>
      </Card>)
  }

  const RolesCard = () => {
    return <Card className={classes.card}>
      <CardHeader title='Roles'/>
      <CardContent>
        <FormGroup row>
          <FormControlLabel
            control={(
              <Checkbox
                color="primary"
                checked={role == AccountRole.Updater}
                onChange={ event => setRole(event.target.checked ? AccountRole.Updater : undefined) }
              />
            )}
            label="Updater"
          />
          <FormControlLabel
            control={(
              <Checkbox
                color="primary"
                checked={role == AccountRole.Builder}
                onChange={ event => setRole(event.target.checked ? AccountRole.Builder : undefined) }
              />
            )}
            label="Builder"
          />
        </FormGroup>
      </CardContent>
    </Card>
  }

  const error = addAccountError?addAccountError.message:changeAccountError?changeAccountError.message:''

  return (
    initialized ? (
      <Card className={clsx(classes.root)}>
        {AccountCard()}
        <Divider />
        {RolesCard()}
        <Divider />
        {error && <Alert className={classes.alert} severity='error'>{error}</Alert>}
        <Box className={classes.controls}>
          <Button className={classes.control}
            color="primary"
            variant="contained"
            component={RouterLink}
            to={props.fromUrl}
          >
            Cancel
          </Button>
          <Button className={classes.control}
            color="primary"
            variant="contained"
            disabled={!validate()}
            onClick={() => submit()}
          >
            {!editAccount?'Add New Account':'Save'}
          </Button>
        </Box>
      </Card>) : null
  );
}

export default AccountEditor;
